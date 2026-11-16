package com.example.saas;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;

import java.io.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bounded snapshot reader -> compressed, RLS-protected chunks -> atomic publication.
 */
@Component
class StreamingExports {
    static final int CHUNK = 262144;
    final JdbcTemplate db;
    final TenantTransactions tenant;
    final ObjectMapper json;
    final MeterRegistry metrics;
    final Semaphore generators = new Semaphore(2, true);
    @Value("${saas.exports.max-bytes:1073741824}")
    long maxBytes = 1073741824;
    @Value("${saas.exports.max-seconds:120}")
    int maxSeconds = 120;
    private HikariDataSource snapshots;

    StreamingExports(
            JdbcTemplate db, TenantTransactions tenant, ObjectMapper json, MeterRegistry metrics) {
        this.db = db;
        this.tenant = tenant;
        this.json = json;
        this.metrics = metrics;
    }

    @jakarta.annotation.PostConstruct
    void validateLimits() {
        if (maxSeconds < 1 || maxSeconds > 600 || maxBytes < 1)
            throw new IllegalArgumentException("Invalid export resource bounds");
    }

    synchronized HikariDataSource snapshots() {
        if (snapshots == null) {
            var source = (HikariDataSource) Objects.requireNonNull(db.getDataSource());
            var config = new HikariConfig();
            config.setJdbcUrl(source.getJdbcUrl());
            var properties = new java.util.Properties();
            properties.putAll(source.getDataSourceProperties());
            properties.setProperty("socketTimeout", Integer.toString(maxSeconds + 10));
            config.setDataSourceProperties(properties);
            config.setUsername(source.getUsername());
            config.setPassword(source.getPassword());
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(2000);
            config.setPoolName("export-snapshots");
            config.setReadOnly(true);
            snapshots = new HikariDataSource(config);
        }
        return snapshots;
    }

    boolean valid(UUID t, UUID job, UUID lease) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "select exists(select 1 from jobs j join tenants t on t.id=j.tenant_id where j.id=? and"
                                + " t.state='ACTIVE' and j.state='RUNNING' and j.lease_token=? and"
                                + " j.lease_until>clock_timestamp() and (j.support_export or exists(select 1 from"
                                + " memberships m where m.tenant_id=j.tenant_id and m.subject=j.requested_by and"
                                + " m.active and m.role in ('TENANT_OWNER','TENANT_ADMIN'))))",
                        Boolean.class,
                        job,
                        lease));
    }

    void checkpoint(UUID t, UUID job, UUID lease, byte[] bytes, int sequence) {
        Boolean done =
                tenant.system(
                        t,
                        c -> {
                            if (!valid(t, job, lease)) throw ApiError.forbidden();
                            db.update(
                                    "update jobs set lease_until=clock_timestamp()+interval '30 seconds' where id=?"
                                            + " and lease_token=? and lease_until<clock_timestamp()+interval '20"
                                            + " seconds'",
                                    job,
                                    lease);
                            if (bytes != null)
                                db.update(
                                        "insert into export_chunks(tenant_id,job_id,generation,sequence,data)"
                                                + " values(?,?,?,?,?)",
                                        t,
                                        job,
                                        lease,
                                        sequence,
                                        bytes);
                            return true;
                        });
        if (!Boolean.TRUE.equals(done)) throw ApiError.forbidden();
    }

    void generate(UUID t, UUID job, UUID lease) throws Exception {
        if (!generators.tryAcquire()) throw new RejectedExecutionException("Export execution busy");
        try {
            checkpoint(t, job, lease, null, 0);
            // A replacement generation never publishes old partially written chunks.
            while (Boolean.TRUE.equals(
                    tenant.system(
                            t,
                            c -> {
                                if (!valid(t, job, lease)) throw ApiError.forbidden();
                                return db.update(
                                        "delete from export_chunks where ctid in (select ctid from export_chunks"
                                                + " where job_id=? and generation<>? limit 500)",
                                        job,
                                        lease)
                                        == 500;
                            }))) {
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxSeconds);
            var digest = MessageDigest.getInstance("SHA-256");
            var sink = new ChunkSink(t, job, lease, deadline);
            var counts = new LinkedHashMap<String, Long>();
            try (Connection connection = snapshots().getConnection()) {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try (var scope =
                             connection.prepareStatement(
                                     "select"
                                             + " set_config('app.current_tenant',?,true),set_config('statement_timeout',?,true),set_config('idle_in_transaction_session_timeout',?,true)")) {
                    scope.setString(1, t.toString());
                    scope.setString(2, maxSeconds + "s");
                    scope.setString(3, maxSeconds + "s");
                    scope.execute();
                }
                try (var compressed =
                             new GZIPOutputStream(new DigestOutputStream(sink, digest), 65536, true);
                     JsonGenerator writer = json.getFactory().createGenerator(compressed)) {
                    writer.disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
                    writer.writeStartObject();
                    writer.writeStringField("tenant_id", t.toString());
                    writer.writeStringField("snapshot", Instant.now().toString());
                    for (String table : List.of("projects", "tasks", "project_members", "task_assignments")) {
                        writer.writeArrayFieldStart(table);
                        long count = 0;
                        try (var statement =
                                     connection.prepareStatement(
                                             "select row_to_json(r)::text from " + table + " r where tenant_id=?",
                                             ResultSet.TYPE_FORWARD_ONLY,
                                             ResultSet.CONCUR_READ_ONLY)) {
                            statement.setObject(1, t);
                            statement.setFetchSize(256);
                            statement.setQueryTimeout(maxSeconds);
                            try (var rows = statement.executeQuery()) {
                                while (rows.next()) {
                                    if (System.nanoTime() > deadline)
                                        throw new IOException("Export deadline exceeded");
                                    writer.writeRawValue(rows.getString(1));
                                    count++;
                                    if (count % 256 == 0) {
                                        writer.flush();
                                        // Authorization checkpoints need not create tiny durable chunks or
                                        // rewrite an otherwise live lease. Full buffers flush independently.
                                        checkpoint(t, job, lease, null, 0);
                                    }
                                }
                            }
                        }
                        writer.writeEndArray();
                        writer.flush();
                        compressed.flush();
                        sink.flush();
                        counts.put(table, count);
                    }
                    writer.writeEndObject();
                }
                connection.commit();
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("format", "application/gzip");
            manifest.put("content_type", "application/json");
            manifest.put("download", "/api/v1/jobs/" + job + "/content");
            manifest.put("sha256", hash);
            manifest.put("compressed_bytes", sink.total);
            manifest.put("chunks", sink.sequence);
            manifest.put("counts", counts);
            String result = json.writeValueAsString(manifest);
            Boolean published =
                    tenant.system(
                            t,
                            c -> {
                                if (!valid(t, job, lease)) throw ApiError.forbidden();
                                int changed =
                                        db.update(
                                                "update jobs set"
                                                        + " state='DONE',result=?::jsonb,export_generation=?,export_expires_at=clock_timestamp()+interval"
                                                        + " '24 hours',lease_token=null,lease_until=null,failure_code=null"
                                                        + " where id=? and lease_token=? and state='RUNNING' and"
                                                        + " lease_until>clock_timestamp()",
                                                result,
                                                lease,
                                                job,
                                                lease);
                                if (changed != 1) throw ApiError.forbidden();
                                tenant.audit(c, "STREAMING_EXPORT_COMPLETED", job);
                                return true;
                            });
            if (!Boolean.TRUE.equals(published)) throw ApiError.forbidden();
            metrics.counter("saas.exports.completed").increment();
        } finally {
            generators.release();
        }
    }

    @PreDestroy
    synchronized void stop() {
        if (snapshots != null) snapshots.close();
    }

    class ChunkSink extends OutputStream {
        final UUID t, job, lease;
        final long deadline;
        final byte[] buffer = new byte[CHUNK];
        int used, sequence;
        long total;

        ChunkSink(UUID t, UUID job, UUID lease, long deadline) {
            this.t = t;
            this.job = job;
            this.lease = lease;
            this.deadline = deadline;
        }

        public void write(int value) throws IOException {
            if (used == CHUNK) flush();
            buffer[used++] = (byte) value;
        }

        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int n = Math.min(length, CHUNK - used);
                System.arraycopy(bytes, offset, buffer, used, n);
                offset += n;
                length -= n;
                used += n;
                if (used == CHUNK) flush();
            }
        }

        public void flush() throws IOException {
            if (used == 0) return;
            if (System.nanoTime() > deadline || total + used > maxBytes)
                throw new IOException("Export resource limit exceeded");
            checkpoint(t, job, lease, Arrays.copyOf(buffer, used), sequence++);
            total += used;
            used = 0;
        }

        public void close() throws IOException {
            flush();
        }
    }
}
