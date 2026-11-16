package com.example.saas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class OperationsApi {
    final JdbcTemplate db;
    final TenantTransactions tenant;
    final Mutations mutations;

    OperationsApi(JdbcTemplate db, TenantTransactions tenant, Mutations mutations) {
        this.db = db;
        this.tenant = tenant;
        this.mutations = mutations;
    }

    static boolean bucket(UUID t, String feature, String subject, int rollout) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((t + ":" + feature + ":" + subject).getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(java.nio.ByteBuffer.wrap(digest).getInt(), 100) < rollout;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    boolean enabled(TenantContext c, String feature) {
        var rows = db.queryForList("select enabled,rollout from feature_flags where id=?", feature);
        return rows.isEmpty()
                || Boolean.TRUE.equals(rows.getFirst().get("enabled"))
                && bucket(
                c.tenantId(),
                feature,
                c.actor(),
                ((Number) rows.getFirst().get("rollout")).intValue());
    }

    @GetMapping("/tenants/{id}/features")
    Object flags(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id, j, false, c -> db.queryForList("select * from feature_flags order by id"));
    }

    @PutMapping("/tenants/{id}/features/{feature}")
    Object flag(
            @AuthenticationPrincipal Jwt j,
            @PathVariable UUID id,
            @PathVariable String feature,
            @Valid @RequestBody Flag b) {
        if (!Set.of("export", "reports").contains(feature)) throw new IllegalArgumentException();
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    if (b.version() == 0) {
                        db.update(
                                "insert into feature_flags values(?,?,?,?,1)",
                                id,
                                feature,
                                b.enabled(),
                                b.rollout());
                    } else if (db.update(
                            "update feature_flags set enabled=?,rollout=?,version=version+1 where id=? and"
                                    + " version=?",
                            b.enabled(),
                            b.rollout(),
                            feature,
                            b.version())
                            == 0) throw ApiError.conflict("Feature version changed");
                    tenant.audit(c, "FEATURE_CHANGED", feature);
                    return db.queryForMap("select * from feature_flags where id=?", feature);
                });
    }

    @PostMapping("/tenants/{id}/reports")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    Object report(
            @AuthenticationPrincipal Jwt j,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    if (!enabled(c, "reports")) throw ApiError.forbidden();
                    return mutations.once(
                            c,
                            key,
                            "report",
                            Map.of(),
                            () -> {
                                int n =
                                        db.update(
                                                "update usage set jobs=jobs+1 where tenant_id=? and"
                                                        + " period=date_trunc('month',now())::date and jobs<(select p.jobs from"
                                                        + " tenants t join plans p on p.name=t.plan where t.id=?)",
                                                id,
                                                id);
                                if (n == 0) throw ApiError.conflict("Job quota exceeded");
                                UUID job = UUID.randomUUID();
                                db.update("insert into jobs(tenant_id,id,kind) values(?,?,'REPORT')", id, job);
                                tenant.audit(c, "REPORT_REQUESTED", job);
                                return Map.of("id", job);
                            });
                });
    }

    @GetMapping("/tenants/{id}/jobs")
    Object jobs(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c -> {
                    c.requireAdmin();
                    return db.queryForList(
                            "select id,kind,state,attempts,next_attempt_at,failure_code,created_at from jobs"
                                    + " order by created_at desc limit 100");
                });
    }

    @PostMapping("/tenants/{id}/jobs/{job}/retry")
    Object retry(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @PathVariable UUID job) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    if (db.update(
                            "update jobs set"
                                    + " state='PENDING',attempts=0,next_attempt_at=now(),failure_code=null where"
                                    + " id=? and state='FAILED'",
                            job)
                            == 0) throw ApiError.conflict("Job is not failed");
                    tenant.audit(c, "JOB_RETRIED", job);
                    return Map.of("id", job);
                });
    }

    @GetMapping("/tenants/{id}/events")
    Object events(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c -> {
                    c.requireAdmin();
                    return Map.of(
                            "outbox",
                            db.queryForList(
                                    "select id,type,published,attempts,next_attempt_at from outbox order by at desc"
                                            + " limit 100"),
                            "projection",
                            db.queryForList("select * from event_projection"),
                            "deferred",
                            db.queryForObject("select count(*) from inbox where not processed", Integer.class));
                });
    }

    @PostMapping("/admin/tenants/{id}/events/{event}/replay")
    Object replay(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @PathVariable UUID event) {
        ControlPlane.platform(j);
        Object result =
                tenant.delivery(
                        id,
                        c -> {
                            if (db.update(
                                    "update outbox set"
                                            + " published=false,attempts=0,next_attempt_at=now(),lease_token=null,lease_until=null"
                                            + " where id=? and (lease_until is null or lease_until<now())",
                                    event)
                                    == 0) throw ApiError.conflict("Event missing or leased");
                            tenant.audit(
                                    new TenantContext(id, j.getSubject(), "TENANT_OWNER", c.correlation()),
                                    "EVENT_REPLAY_REQUESTED",
                                    event);
                            return Map.of("id", event);
                        });
        if (result == null) throw ApiError.forbidden();
        return result;
    }

    @PatchMapping("/tenants/{id}/members/{subject}/state")
    Object memberState(
            @AuthenticationPrincipal Jwt j,
            @PathVariable UUID id,
            @PathVariable String subject,
            @RequestBody MemberState b) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    int seats =
                            db.queryForObject(
                                    "select p.seats from tenants t join plans p on p.name=t.plan where t.id=?",
                                    Integer.class,
                                    id);
                    boolean alreadyActive =
                            Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "select exists(select 1 from memberships where tenant_id=? and subject=? and"
                                                    + " active)",
                                            Boolean.class,
                                            id,
                                            subject));
                    if (b.active()
                            && !alreadyActive
                            && db.queryForObject(
                            "select count(*) from memberships where tenant_id=? and active",
                            Integer.class,
                            id)
                            >= seats) throw ApiError.conflict("Seat quota exceeded");
                    if (db.update(
                            "update memberships set active=? where tenant_id=? and subject=? and"
                                    + " role<>'TENANT_OWNER'",
                            b.active(),
                            id,
                            subject)
                            == 0) throw ApiError.missing();
                    tenant.audit(c, b.active() ? "MEMBER_REACTIVATED" : "MEMBER_SUSPENDED", subject);
                    return Map.of("active", b.active());
                });
    }

    @PostMapping("/tenants/{id}/owner")
    Object transfer(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Transfer b) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    if (!c.role().equals("TENANT_OWNER")) throw ApiError.forbidden();
                    if (db.update(
                            "update memberships set role='TENANT_OWNER' where tenant_id=? and subject=? and"
                                    + " active",
                            id,
                            b.subject())
                            == 0) throw ApiError.missing();
                    if (!b.subject().equals(c.actor()))
                        db.update(
                                "update memberships set role='TENANT_ADMIN' where tenant_id=? and subject=?",
                                id,
                                c.actor());
                    db.update("update tenants set owner_subject=? where id=?", b.subject(), id);
                    tenant.audit(c, "OWNER_TRANSFERRED", b.subject());
                    return Map.of("owner", b.subject());
                });
    }

    @GetMapping("/tenants/{id}/billing-history")
    Object history(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c -> {
                    c.requireAdmin();
                    return db.queryForList("select * from billing_history order by at desc limit 100");
                });
    }

    @PostMapping("/tenants/{id}/subscription/cancel")
    Object cancel(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.control(
                id,
                j,
                c -> {
                    if (!c.role().equals("TENANT_OWNER")) throw ApiError.forbidden();
                    db.update(
                            "update tenants set"
                                    + " cancel_at_period_end=true,cancel_effective_at=date_trunc('month',now())+interval"
                                    + " '1 month' where id=?",
                            id);
                    tenant.audit(c, "SUBSCRIPTION_CANCELLATION_SCHEDULED", id);
                    return Map.of("cancelAtPeriodEnd", true);
                });
    }

    record Flag(boolean enabled, @Min(0) @Max(100) int rollout, @Min(0) long version) {
    }

    record MemberState(boolean active) {
    }

    record Transfer(@NotBlank @Size(max = 200) String subject) {
    }
}
