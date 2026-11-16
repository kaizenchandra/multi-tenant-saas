package com.example.saas;

import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable upper-bound quota reservations, never cached identity or authorization.
 */
@Component
class QuotaBudgets {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate writes, reads;
    private final MeterRegistry metrics;
    private final Map<UUID, Slot> slots = new LinkedHashMap<>();
    private final ConcurrentHashMap<UUID, Close> pending = new ConcurrentHashMap<>();
    @Value("${saas.quotas.leased-requests:${saas.quotas.leased-reads:false}}")
    boolean enabled = false;
    @Value("${saas.quotas.grant-size:16}")
    int grantSize = 16;

    QuotaBudgets(
            JdbcTemplate db,
            ObjectMapper json,
            PlatformTransactionManager manager,
            MeterRegistry metrics) {
        this.db = db;
        this.json = json;
        this.metrics = metrics;
        io.micrometer.core.instrument.Gauge.builder(
                        "saas.api.budget.pending_returns", pending, Map::size)
                .register(metrics);
        io.micrometer.core.instrument.Gauge.builder(
                        "saas.api.budget.cached_tenants",
                        this,
                        q -> {
                            synchronized (q.slots) {
                                return q.slots.size();
                            }
                        })
                .register(metrics);
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setTimeout(5);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setReadOnly(true);
        reads.setTimeout(5);
    }

    private static org.springframework.web.server.ResponseStatusException busy(String reason) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, reason);
    }

    @jakarta.annotation.PostConstruct
    void validate() {
        if (grantSize < 1 || grantSize > 16)
            throw new IllegalArgumentException("Quota grant size must be between 1 and 16");
    }

    private JsonNode query(TransactionTemplate tx, String sql, Object... values) {
        String raw = tx.execute(s -> db.queryForObject(sql, String.class, values));
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void admit(UUID tenant, String actor) {
        var auth = query(reads, "select authorize_api_budget(?,?)::text", tenant, actor);
        check(auth);
        for (int i = 0; i < 3; i++) {
            var ticket = prepare(tenant, actor);
            if (consume(ticket)) return;
        }
        throw busy("Quota credit busy; retry with backoff");
    }

    Ticket prepare(UUID tenant, String actor) {
        return reserve(tenant, actor);
    }

    boolean consume(Ticket ticket) {
        if (!ticket.local()) return true;
        Slot slot;
        synchronized (slots) {
            slot = slots.get(ticket.tenant());
            if (slot == null) return false;
            slot.references++;
        }
        try {
            // Never wait for a Java monitor while holding a DB tenant fence: grant RPCs may
            // themselves wait behind an exclusive DB fence. CAS avoids that lock-order cycle.
            for (int attempt = 0; attempt < 4; attempt++) {
                var b = slot.budget.get();
                if (b == null
                        || !b.id().equals(ticket.lease())
                        || b.remaining() <= 0
                        || System.nanoTime() >= b.deadline()) return false;
                if (slot.budget.compareAndSet(b, b.take())) {
                    metrics.counter("saas.api.budget", "result", "local").increment();
                    return true;
                }
            }
            return false;
        } finally {
            synchronized (slots) {
                slot.references--;
            }
        }
    }

    private Ticket reserve(UUID tenant, String actor) {
        Slot slot;
        synchronized (slots) {
            slot = slots.get(tenant);
            if (slot == null) {
                if (slots.size() >= 1024) throw busy("Quota cache is busy");
                slot = new Slot();
                slots.put(tenant, slot);
            }
            slot.references++;
        }
        try {
            synchronized (slot) {
                long now = System.nanoTime();
                Budget old = slot.budget.get();
                if (pending.size() >= 4096)
                    throw busy("Quota reconciliation capacity busy; retry with backoff");
                // Leased mode deliberately reserves a bounded batch even for a cold tenant.
                // A per-tenant arrival-time heuristic forms a positive feedback loop: slow
                // commits lower throughput, disable batching and create still more commits.
                int requested = grantSize;
                slot.last = now;
                if (old != null && old.remaining() > 0 && now < old.deadline()) {
                    return new Ticket(tenant, old.id(), old.fingerprint(), true);
                }
                old = slot.budget.getAndSet(null);
                UUID previous = old == null || old.amount() == 1 ? null : old.id();
                int unused = old == null ? 0 : old.remaining();
                UUID id = UUID.randomUUID();
                slot.budget.set(null);
                long began = System.nanoTime();
                JsonNode grant;
                try {
                    try {
                        grant =
                                query(
                                        writes,
                                        "select grant_api_budget(?,?,?,?,?,?)::text",
                                        tenant,
                                        actor,
                                        requested,
                                        id,
                                        previous,
                                        unused);
                    } catch (org.springframework.dao.DataAccessResourceFailureException
                             | org.springframework.transaction.TransactionException uncertain) {
                        // One bounded retry uses the SAME durable ID, so a lost grant acknowledgment
                        // cannot reserve a second budget. Expired replays fail closed.
                        metrics.counter("saas.api.budget", "result", "grant_retry").increment();
                        grant =
                                query(
                                        writes,
                                        "select grant_api_budget(?,?,?,?,?,?)::text",
                                        tenant,
                                        actor,
                                        requested,
                                        id,
                                        previous,
                                        unused);
                    }
                } catch (RuntimeException e) {
                    if (previous != null) queueReturn(new Close(tenant, previous, unused));
                    throw e;
                }
                try {
                    check(grant);
                } catch (RuntimeException denied) {
                    if (previous != null) queueReturn(new Close(tenant, previous, unused));
                    throw denied;
                }
                int amount = grant.path("amount").asInt();
                metrics
                        .timer("saas.api.budget.grant.rpc")
                        .record(System.nanoTime() - began, TimeUnit.NANOSECONDS);
                metrics.counter("saas.api.budget.credits.granted").increment(amount);
                metrics
                        .counter("saas.api.budget", "result", amount == 1 ? "single_grant" : "batch_grant")
                        .increment();
                long deadline = began + TimeUnit.MILLISECONDS.toNanos(grant.path("ttl_ms").asLong());
                if (amount > 1 && deadline <= System.nanoTime())
                    metrics.counter("saas.api.budget", "result", "grant_expired_on_arrival").increment();
                // The first token is admitted durably at the grant timestamp. Only later local tokens use
                // TTL.
                slot.budget.set(
                        new Budget(id, grant.path("fingerprint").asText(), amount, amount - 1, deadline));
                metrics.counter("saas.api.budget", "result", "grant").increment();
                return new Ticket(tenant, id, slot.budget.get().fingerprint(), false);
            }
        } finally {
            synchronized (slots) {
                slot.references--;
                if (slot.references == 0 && slot.budget.get() == null) slots.remove(tenant, slot);
            }
        }
    }

    void invalidate(UUID id) {
        synchronized (slots) {
            var slot = slots.get(id);
            if (slot != null)
                slot.budget.updateAndGet(
                        b ->
                                b == null
                                        ? null
                                        : new Budget(b.id(), b.fingerprint(), b.amount(), b.remaining(), 0));
        }
    }

    private void check(JsonNode result) {
        String status = result.path("status").asText();
        if (status.equals("DENIED")) {
            metrics.counter("saas.authorization.denied").increment();
            throw ApiError.forbidden();
        }
        if (!status.equals("OK")) {
            metrics.counter("saas.quota.rejected", "reason", status.toLowerCase(Locale.ROOT)).increment();
            throw busy("Tenant " + status.toLowerCase(Locale.ROOT) + " quota exceeded");
        }
    }

    @Scheduled(fixedDelay = 250)
    void clean() {
        if (enabled) flush(false);
    }

    void flush(boolean force) {
        var expired = new ArrayList<Map.Entry<UUID, Slot>>();
        synchronized (slots) {
            for (var e : slots.entrySet()) expired.add(Map.entry(e.getKey(), e.getValue()));
        }
        for (var e : expired) {
            Slot slot = e.getValue();
            var b = slot.budget.get();
            if (b != null
                    && (force
                    || System.nanoTime() >= b.deadline()
                    && System.nanoTime() - slot.last >= TimeUnit.SECONDS.toNanos(1))
                    && slot.budget.compareAndSet(b, null)
                    && b.amount() > 1) queueReturn(new Close(e.getKey(), b.id(), b.remaining()));
            if (force
                    || slot.budget.get() == null
                    && System.nanoTime() - slot.last > TimeUnit.SECONDS.toNanos(1)) {
                synchronized (slots) {
                    if (slot.references == 0 && slot.budget.get() == null) slots.remove(e.getKey(), slot);
                }
            }
        }
        int count = 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(force ? 5000 : 100);
        for (Close c : pending.values()) {
            if (count++ >= 100 || System.nanoTime() >= deadline) break;
            try {
                writes.execute(
                        s ->
                                db.queryForObject(
                                        "select close_api_budget(?,?,?)",
                                        Boolean.class,
                                        c.tenant(),
                                        c.id(),
                                        c.unused()));
                pending.remove(c.id(), c);
            } catch (RuntimeException e) {
                metrics.counter("saas.api.budget", "result", "return_failure").increment();
            }
        }
    }

    private void queueReturn(Close c) {
        if (pending.size() < 4096 || pending.containsKey(c.id())) pending.putIfAbsent(c.id(), c);
        else metrics.counter("saas.api.budget", "result", "return_backlog_full").increment();
        // On overload, the durable grant remains reserved and is reconciled conservatively.
    }

    @PreDestroy
    void shutdown() {
        if (enabled) flush(true);
    }

    static class Slot {
        final java.util.concurrent.atomic.AtomicReference<Budget> budget =
                new java.util.concurrent.atomic.AtomicReference<>();
        volatile long last;
        int references;
    }

    record Budget(UUID id, String fingerprint, int amount, int remaining, long deadline) {
        Budget take() {
            return new Budget(id, fingerprint, amount, remaining - 1, deadline);
        }
    }

    record Close(UUID tenant, UUID id, int unused) {
    }

    // A ticket is not authorization. Only a freshly authorized business boundary consumes it.
    record Ticket(UUID tenant, UUID lease, String fingerprint, boolean local) {
    }
}
