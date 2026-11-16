package com.example.saas;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ExportDownloads {
    final TenantTransactions tenant;
    final JdbcTemplate db;
    final ObjectMapper json;
    final Semaphore active = new Semaphore(2, true);
    final Set<UUID> tenants = ConcurrentHashMap.newKeySet();

    ExportDownloads(TenantTransactions tenant, JdbcTemplate db, ObjectMapper json) {
        this.tenant = tenant;
        this.db = db;
        this.json = json;
    }

    static org.springframework.web.server.ResponseStatusException busy() {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Export download capacity busy");
    }

    Manifest manifest(TenantContext c, UUID id) {
        c.requireAdmin();
        var rows =
                db.queryForList(
                        "select export_generation,result::text as manifest,export_expires_at>clock_timestamp()"
                                + " as live from jobs where id=? and kind='EXPORT' and state='DONE'",
                        id);
        if (rows.isEmpty()) throw ApiError.missing();
        var row = rows.getFirst();
        if (!Boolean.TRUE.equals(row.get("live")))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GONE, "Export expired");
        try {
            var m = json.readTree(row.get("manifest").toString());
            return new Manifest(
                    (UUID) row.get("export_generation"),
                    m.path("compressed_bytes").asLong(),
                    m.path("sha256").asText(),
                    m.path("chunks").asInt());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @GetMapping("/jobs/{id}/content")
    void download(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Tenant-ID") UUID t,
            @PathVariable UUID id,
            HttpServletResponse response)
            throws IOException {
        transfer(jwt, t, id, false, response);
    }

    @GetMapping("/admin/tenants/{t}/exports/{id}")
    Object supportStatus(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID t, @PathVariable UUID id) {
        ControlPlane.platform(jwt);
        return checked(
                t,
                jwt,
                true,
                false,
                c -> {
                    var rows =
                            db.queryForList(
                                    "select id,state,attempts,failure_code,export_expires_at,result::text as result"
                                            + " from jobs where id=? and kind='EXPORT'",
                                    id);
                    if (rows.isEmpty()) throw ApiError.missing();
                    var result = new HashMap<String, Object>(rows.getFirst());
                    if (result.get("result") != null)
                        try {
                            result.put("result", json.readTree(result.get("result").toString()));
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw new IllegalStateException(e);
                        }
                    tenant.audit(c, "SUPPORT_EXPORT_STATUS_READ", id);
                    return result;
                });
    }

    @GetMapping("/admin/tenants/{t}/exports/{id}/content")
    void support(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID t,
            @PathVariable UUID id,
            HttpServletResponse response)
            throws IOException {
        ControlPlane.platform(jwt);
        transfer(jwt, t, id, true, response);
    }

    private <T> T checked(
            UUID t,
            Jwt jwt,
            boolean support,
            boolean meter,
            java.util.function.Function<TenantContext, T> work) {
        if (!support)
            return meter ? tenant.user(t, jwt, false, work) : tenant.checkedRead(t, jwt, work);
        ControlPlane.platform(jwt);
        if (jwt.getExpiresAt() != null && !java.time.Instant.now().isBefore(jwt.getExpiresAt()))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Access token expired");
        T result =
                tenant.system(
                        t,
                        c ->
                                work.apply(
                                        new TenantContext(t, jwt.getSubject(), "TENANT_OWNER", c.correlation())));
        if (result == null) throw ApiError.forbidden();
        return result;
    }

    private void transfer(Jwt jwt, UUID t, UUID id, boolean support, HttpServletResponse response)
            throws IOException {
        Manifest metadata = checked(t, jwt, support, true, c -> manifest(c, id));
        if (!tenants.add(t)) throw busy();
        boolean acquired = active.tryAcquire();
        if (!acquired) {
            tenants.remove(t);
            throw busy();
        }
        try {
            java.util.function.Function<TenantContext, Boolean> record =
                    c -> {
                        c.requireAdmin();
                        manifest(c, id);
                        tenant.audit(
                                c, support ? "SUPPORT_EXPORT_DOWNLOAD_STARTED" : "EXPORT_DOWNLOAD_STARTED", id);
                        return true;
                    };
            if (support) checked(t, jwt, true, false, record);
            else tenant.control(t, jwt, record);
            response.setContentType("application/gzip");
            response.setHeader("Content-Disposition", "attachment; filename=export-" + id + ".json.gz");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("ETag", "\"" + metadata.sha() + "\"");
            response.setContentLengthLong(metadata.bytes());
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            long sent = 0;
            for (int i = 0; i < metadata.chunks(); i++) {
                if (System.nanoTime() > deadline) throw new IOException("Download deadline exceeded");
                final int sequence = i;
                byte[] bytes =
                        checked(
                                t,
                                jwt,
                                support,
                                false,
                                c -> {
                                    Manifest current = manifest(c, id);
                                    if (!current.generation().equals(metadata.generation())) throw ApiError.missing();
                                    var rows =
                                            db.queryForList(
                                                    "select data from export_chunks where job_id=? and generation=? and"
                                                            + " sequence=?",
                                                    id,
                                                    metadata.generation(),
                                                    sequence);
                                    if (rows.isEmpty()) throw ApiError.missing();
                                    return (byte[]) rows.getFirst().get("data");
                                });
                response.getOutputStream().write(bytes);
                sent += bytes.length;
            }
            if (sent != metadata.bytes()) throw new IOException("Incomplete export");
        } finally {
            active.release();
            tenants.remove(t);
        }
    }

    record Manifest(UUID generation, long bytes, String sha, int chunks) {
    }
}
