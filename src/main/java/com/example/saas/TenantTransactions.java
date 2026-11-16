package com.example.saas;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class TenantTransactions {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final TransactionTemplate reads;
    final MeterRegistry metrics;
    final Admission admission;
    final QuotaBudgets budgets;

    @org.springframework.beans.factory.annotation.Autowired
    TenantTransactions(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            MeterRegistry metrics,
            Admission admission,
            QuotaBudgets budgets) {
        this.db = db;
        this.metrics = metrics;
        this.admission = admission;
        this.budgets = budgets;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setReadOnly(true);
        reads.setTimeout(10);
    }

    TenantTransactions(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            MeterRegistry metrics,
            Admission admission) {
        this(
                db,
                manager,
                metrics,
                admission,
                new QuotaBudgets(db, new com.fasterxml.jackson.databind.ObjectMapper(), manager, metrics));
    }

    <T> T user(UUID id, Jwt jwt, boolean write, Function<TenantContext, T> work) {
        return execute(id, jwt, write, write, true, work);
    }

    // Billing recovery mutates control metadata but must remain available to a read-only
    // subscription.
    <T> T control(UUID id, Jwt jwt, Function<TenantContext, T> work) {
        return execute(id, jwt, false, true, false, work);
    }

    <T> T checkedRead(UUID id, Jwt jwt, Function<TenantContext, T> work) {
        return execute(id, jwt, false, false, false, work);
    }

    private <T> T execute(
            UUID id,
            Jwt jwt,
            boolean write,
            boolean exclusive,
            boolean meter,
            Function<TenantContext, T> work) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return executeOnce(id, jwt, write, exclusive, meter, work);
            } catch (RetryCredit lost) {
                if (attempt == 2)
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                            "Quota credit busy; retry with backoff");
            }
        }
        throw new IllegalStateException();
    }

    private <T> T executeOnce(
            UUID id,
            Jwt jwt,
            boolean write,
            boolean exclusive,
            boolean meter,
            Function<TenantContext, T> work) {
        Objects.requireNonNull(id);
        if (jwt.getExpiresAt() != null && !java.time.Instant.now().isBefore(jwt.getExpiresAt()))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Access token expired");
        String claim = jwt.getClaimAsString("tenant_id");
        if (claim != null && !claim.equals(id.toString())) throw ApiError.forbidden();
        try (var permit = admission.acquire(id, jwt.getSubject())) {
            QuotaBudgets.Ticket quotaTicket =
                    meter && budgets.enabled ? budgets.prepare(id, jwt.getSubject()) : null;
            if (meter && (write || !exclusive) && quotaTicket == null) {
                long started = System.nanoTime();
                String reserved =
                        tx.execute(
                                s ->
                                        db.queryForObject(
                                                "select reserve_api_request(?,?)", String.class, id, jwt.getSubject()));
                metrics
                        .timer("saas.quota.reservation")
                        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                if (!"OK".equals(reserved)) {
                    if ("DENIED".equals(reserved)) {
                        metrics.counter("saas.authorization.denied").increment();
                        throw ApiError.forbidden();
                    }
                    metrics
                            .counter("saas.quota.rejected", "reason", reserved.toLowerCase(java.util.Locale.ROOT))
                            .increment();
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                            "Tenant " + reserved.toLowerCase(java.util.Locale.ROOT) + " quota exceeded");
                }
            }
            // Resolve live membership before occupying a victim's tenant lane. Retain the
            // permit through commit/rollback, not merely through the callback's business work.
            AutoCloseable[] verifiedPermit = {() -> {
            }};
            try {
                return (exclusive ? tx : reads)
                        .execute(
                                s -> {
                                    var rows =
                                            db.queryForList(
                                                    "select subscription,trial_ends_at,data_epoch,member_role as"
                                                            + " role,fingerprint from establish_tenant(?,?,?)",
                                                    id,
                                                    jwt.getSubject(),
                                                    exclusive);
                                    if (rows.isEmpty()) throw ApiError.forbidden();
                                    if (write || !exclusive) verifiedPermit[0] = admission.verified(id);
                                    var row = rows.getFirst();
                                    if (quotaTicket != null
                                            && !quotaTicket.fingerprint().equals(row.get("fingerprint"))) {
                                        budgets.invalidate(id);
                                        // No business work or local credit consumption has occurred. Retry
                                        // outside this transaction, with fresh authorization and a new grant.
                                        throw new RetryCredit();
                                    }
                                    if (quotaTicket != null && !budgets.consume(quotaTicket)) throw new RetryCredit();
                                    var c =
                                            new TenantContext(
                                                    id,
                                                    jwt.getSubject(),
                                                    row.get("role").toString(),
                                                    UUID.randomUUID(),
                                                    ((Number) row.get("data_epoch")).longValue());
                                    if (write) {
                                        c.requireWrite();
                                        String status = row.get("subscription").toString();
                                        if (!Set.of("ACTIVE", "TRIALING").contains(status)
                                                || status.equals("TRIALING")
                                                && ((java.sql.Timestamp) row.get("trial_ends_at"))
                                                .toInstant()
                                                .isBefore(java.time.Instant.now()))
                                            throw new org.springframework.web.server.ResponseStatusException(
                                                    org.springframework.http.HttpStatus.PAYMENT_REQUIRED,
                                                    "Subscription is read-only or trial expired");
                                    }
                                    return work.apply(c);
                                });
            } finally {
                verifiedPermit[0].close();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    <T> T system(UUID id, Function<TenantContext, T> work) {
        return scoped(id, Set.of("ACTIVE"), false, work);
    }

    <T> T systemExclusive(UUID id, Function<TenantContext, T> work) {
        return scoped(id, Set.of("ACTIVE"), true, work);
    }

    <T> T delivery(UUID id, Function<TenantContext, T> work) {
        return scoped(id, Set.of("ACTIVE", "SUSPENDED"), false, work);
    }

    private <T> T scoped(
            UUID id, Set<String> states, boolean exclusive, Function<TenantContext, T> work) {
        return tx.execute(
                s -> {
                    scopeAndFence(id, exclusive);
                    var rows = db.queryForList("select state from tenants where id=?", id);
                    if (rows.isEmpty() || !states.contains(rows.getFirst().get("state"))) return null;
                    return work.apply(
                            new TenantContext(id, "system:worker", "TENANT_OWNER", UUID.randomUUID()));
                });
    }

    void fence(UUID id, boolean exclusive) {
        db.queryForMap(
                "select "
                        + (exclusive ? "pg_advisory_xact_lock" : "pg_advisory_xact_lock_shared")
                        + "(tenant_lock_key(?))",
                id);
    }

    void scopeAndFence(UUID id, boolean exclusive) {
        Objects.requireNonNull(id);
        db.queryForMap(
                "select set_config('app.current_tenant',?,true),"
                        + (exclusive ? "pg_advisory_xact_lock" : "pg_advisory_xact_lock_shared")
                        + "(tenant_lock_key(?))",
                id.toString(),
                id);
    }

    void scope(UUID id) {
        Objects.requireNonNull(id);
        db.queryForObject(
                "select set_config('app.current_tenant',?,true)", String.class, id.toString());
    }

    void audit(TenantContext c, String action, Object resource) {
        db.update(
                "insert into audit(tenant_id,id,actor,action,resource,correlation) values(?,?,?,?,?,?)",
                c.tenantId(),
                UUID.randomUUID(),
                c.actor(),
                action,
                resource.toString(),
                c.correlation());
    }

    static class RetryCredit extends RuntimeException {
    }
}
