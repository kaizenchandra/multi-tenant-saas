package com.example.saas;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "saas.workers", havingValue = "true")
class DurableWork {
    final JdbcTemplate db;
    final TenantTransactions tenant;
    final ObjectMapper json;
    final KafkaTemplate<String, String> kafka;
    final MeterRegistry metrics;
    final boolean messaging;
    final java.util.Set<UUID> runningTenants = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final java.util.concurrent.atomic.AtomicLong brokerRetryAfter =
            new java.util.concurrent.atomic.AtomicLong();
    @org.springframework.beans.factory.annotation.Autowired
    StreamingExports exports;
    UUID cursor = new UUID(0, 0);
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("outboxExecutor")
    java.util.concurrent.Executor publisher;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("jobExecutor")
    java.util.concurrent.Executor jobs;

    DurableWork(
            JdbcTemplate db,
            TenantTransactions tenant,
            ObjectMapper json,
            KafkaTemplate<String, String> kafka,
            MeterRegistry metrics,
            @Value("${saas.messaging}") boolean messaging) {
        this.db = db;
        this.tenant = tenant;
        this.json = json;
        this.kafka = kafka;
        this.metrics = metrics;
        this.messaging = messaging;
    }

    @Scheduled(fixedDelay = 60000)
    void subscriptions() {
        for (UUID id :
                db.queryForList(
                        "select id from tenants where state='ACTIVE' and ((subscription='TRIALING' and"
                                + " trial_ends_at<now()) or (cancel_at_period_end and cancel_effective_at<=now()))"
                                + " order by id limit 32",
                        UUID.class))
            tenant.systemExclusive(
                    id,
                    c -> {
                        String status =
                                db.queryForObject(
                                        "select case when cancel_at_period_end then 'CANCELLED' else 'SUSPENDED' end"
                                                + " from tenants where id=?",
                                        String.class,
                                        id);
                        db.update(
                                "update tenants set"
                                        + " subscription=?,cancel_at_period_end=false,cancel_effective_at=null where"
                                        + " id=?",
                                status,
                                id);
                        db.update(
                                "insert into billing_history select ?,?,?,plan,'system:billing',now() from tenants"
                                        + " where id=?",
                                id,
                                UUID.randomUUID(),
                                status,
                                id);
                        tenant.audit(c, "SUBSCRIPTION_PERIOD_ENDED", id);
                        return null;
                    });
    }

    @Scheduled(fixedDelay = 1000)
    void tick() {
        var ids =
                db.queryForList(
                        "select id from tenants where state='ACTIVE' and id>? order by id limit 32",
                        UUID.class,
                        cursor);
        if (ids.isEmpty()) {
            cursor = new UUID(0, 0);
            return;
        }
        for (UUID id : ids) {
            cursor = id;
            try {
                if (runningTenants.add(id)) {
                    try {
                        jobs.execute(
                                () -> {
                                    try {
                                        runOne(id);
                                    } finally {
                                        runningTenants.remove(id);
                                    }
                                });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        runningTenants.remove(id);
                        metrics.counter("saas.jobs.admission_rejected").increment();
                    }
                }
                if (messaging) {
                    try {
                        publisher.execute(
                                () -> {
                                    try {
                                        dispatchOne(id);
                                    } catch (Exception e) {
                                        metrics.counter("saas.worker.failures").increment();
                                    }
                                });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        metrics.counter("saas.outbox.admission_rejected").increment();
                    }
                }
                tenant.system(
                        id,
                        c -> {
                            db.update(
                                    "delete from export_chunks where ctid in (select e.ctid from export_chunks e join"
                                            + " jobs j on j.tenant_id=e.tenant_id and j.id=e.job_id where (j.state='DONE'"
                                            + " and j.export_expires_at<clock_timestamp()) or (j.state='FAILED' and"
                                            + " j.created_at<clock_timestamp()-interval '1 hour') limit 100)");
                            db.queryForObject(
                                    "select reconcile_api_budgets(?,?,?)::text",
                                    String.class,
                                    id,
                                    "system:quota",
                                    100);
                            db.update(
                                    "delete from api_budgets where ctid in (select ctid from api_budgets where closed"
                                            + " and settlement='PROVEN' and created_at<now()-interval '1 hour' limit"
                                            + " 1000)");
                            db.update(
                                    "delete from idempotency where ctid in (select ctid from idempotency where"
                                            + " expires_at<=now() limit 100)");
                            for (UUID event :
                                    db.queryForList(
                                            "select id from inbox where not processed order by at limit 10",
                                            UUID.class)) {
                                new EventConsumer(tenant, db, json).process(c, event);
                            }
                            return null;
                        });
            } catch (Exception ex) {
                metrics.counter("saas.worker.failures").increment();
            }
        }
    }

    Map<String, Object> claimJob(UUID id) {
        return tenant.system(
                id,
                c -> {
                    // Recover attempts whose process died after committing its lease.
                    db.update(
                            "update jobs set"
                                    + " state='FAILED',failure_code='LEASE_EXHAUSTED',lease_token=null,lease_until=null"
                                    + " where state='RUNNING' and lease_until<now() and attempts>=3");
                    var rows =
                            db.queryForList(
                                    "select id,kind from jobs where ((state='PENDING' and next_attempt_at<=now()) or"
                                            + " (state='RUNNING' and lease_until<now())) and attempts<3 order by"
                                            + " created_at limit 1 for update skip locked");
                    if (rows.isEmpty()) return null;
                    var row = new HashMap<String, Object>(rows.getFirst());
                    UUID token = UUID.randomUUID();
                    row.put("lease", token);
                    db.update(
                            "update jobs set"
                                    + " state='RUNNING',attempts=attempts+1,lease_token=?,lease_until=now()+interval"
                                    + " '30 seconds' where id=?",
                            token,
                            row.get("id"));
                    return row;
                });
    }

    void runOne(UUID id) {
        var job = claimJob(id);
        if (job == null) return;
        try {
            if ("EXPORT".equals(job.get("kind"))) {
                exports.generate(id, (UUID) job.get("id"), (UUID) job.get("lease"));
                return;
            }
            tenant.system(
                    id,
                    c -> {
                        if (db.queryForObject(
                                "select count(*) from jobs where id=? and state='RUNNING' and lease_token=? and"
                                        + " lease_until>now()",
                                Integer.class,
                                job.get("id"),
                                job.get("lease"))
                                == 0) return null;
                        String result =
                                db.queryForObject(
                                        "select jsonb_build_object('projects',(select coalesce(jsonb_agg(s),'[]') from"
                                                + " (select status,count(*) as count from projects group by"
                                                + " status)s),'tasks',(select coalesce(jsonb_agg(s),'[]') from (select"
                                                + " status,count(*) as count from tasks group by status)s))::text",
                                        String.class);
                        db.update(
                                "update jobs set"
                                        + " state='DONE',result=?::jsonb,lease_until=null,lease_token=null,failure_code=null"
                                        + " where id=? and lease_token=?",
                                result,
                                job.get("id"),
                                job.get("lease"));
                        tenant.audit(c, "JOB_COMPLETED", job.get("id"));
                        metrics.counter("saas.jobs.completed", "kind", job.get("kind").toString()).increment();
                        return null;
                    });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            tenant.delivery(
                    id,
                    c -> {
                        db.update(
                                "update jobs set"
                                        + " state='PENDING',attempts=attempts-1,lease_token=null,lease_until=null,next_attempt_at=clock_timestamp()+interval"
                                        + " '2 seconds' where id=? and lease_token=?",
                                job.get("id"),
                                job.get("lease"));
                        return null;
                    });
        } catch (Exception e) {
            failJob(id, (UUID) job.get("id"), (UUID) job.get("lease"));
        }
    }

    void failJob(UUID tenantId, UUID job, UUID lease) {
        tenant.delivery(
                tenantId,
                c -> {
                    db.update(
                            "update jobs set state=case when attempts>=3 then 'FAILED' else 'PENDING'"
                                    + " end,next_attempt_at=now()+make_interval(secs=>power(2,attempts)::int),lease_until=null,lease_token=null,failure_code='EXECUTION_FAILED'"
                                    + " where id=? and lease_token=?",
                            job,
                            lease);
                    tenant.audit(c, "JOB_ATTEMPT_FAILED", job);
                    metrics.counter("saas.jobs.failed").increment();
                    return null;
                });
    }

    void dispatchOne(UUID id) {
        if (System.currentTimeMillis() < brokerRetryAfter.get()) return;
        var row =
                tenant.system(
                        id,
                        c -> {
                            var rows =
                                    db.queryForList(
                                            "select * from outbox where not published and attempts<5 and"
                                                    + " next_attempt_at<=now() and (lease_until is null or lease_until<now())"
                                                    + " order by at limit 1 for update skip locked");
                            if (rows.isEmpty()) return null;
                            var e = new HashMap<String, Object>(rows.getFirst());
                            UUID lease = UUID.randomUUID();
                            db.update(
                                    "update outbox set attempts=attempts+1,lease_token=?,lease_until=now()+interval"
                                            + " '30 seconds' where id=?",
                                    lease,
                                    e.get("id"));
                            e.put("lease", lease);
                            return e;
                        });
        if (row == null) return;
        boolean sent = false;
        try {
            var envelope = new LinkedHashMap<String, Object>();
            for (String field :
                    List.of(
                            "tenant_id",
                            "id",
                            "aggregate_id",
                            "version",
                            "type",
                            "schema_version",
                            "correlation",
                            "at"))
                envelope.put(field, row.get(field));
            kafka
                    .send("saas.events", id.toString(), json.writeValueAsString(envelope))
                    .get(3, TimeUnit.SECONDS);
            sent = true;
            brokerRetryAfter.set(0);
        } catch (Exception e) {
            brokerRetryAfter.set(System.currentTimeMillis() + 5000);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        boolean success = sent;
        tenant.delivery(
                id,
                c -> {
                    db.update(
                            "update outbox set"
                                    + " published=?,lease_token=null,lease_until=null,next_attempt_at=now()+make_interval(secs=>least(60,power(2,attempts)::int))"
                                    + " where id=? and lease_token=?",
                            success,
                            row.get("id"),
                            row.get("lease"));
                    metrics.counter(success ? "saas.outbox.published" : "saas.outbox.retry").increment();
                    return null;
                });
    }
}
