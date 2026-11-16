package com.example.saas;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds JDBC demand while allowing concurrent reads for one authenticated tenant.
 */
@Component
class Admission {
    private final Map<String, Integer> active = new HashMap<>();
    private final Map<UUID, Integer> verified = new HashMap<>();
    private final int capacity;
    private final Semaphore global;
    private final Semaphore ingress;
    private final Map<String, Integer> incoming = new HashMap<>();
    private final int perTenant;
    private final Map<String, SelectorWindow> selectorWindows = new HashMap<>();
    private final long waitMillis;
    private final Map<String, Window> windows = new HashMap<>();
    @Value("${saas.admission.selector-rps:60}")
    int selectorRps = 60;
    @Value("${saas.admission.principal-rps:1000}")
    int principalRps = 1000;
    private long lastSweep;

    Admission(
            @Value("${saas.admission.global-capacity:10}") int capacity,
            @Value("${saas.admission.per-tenant:6}") int perTenant,
            @Value("${saas.admission.wait-ms:50}") long waitMillis) {
        if (capacity < 1
                || perTenant < 1
                || perTenant > capacity
                || waitMillis < 0
                || waitMillis > 1000) throw new IllegalArgumentException("Invalid admission bounds");
        this.global = new Semaphore(capacity, true);
        this.ingress = new Semaphore(capacity * 2, true);
        this.capacity = capacity;
        this.perTenant = perTenant;
        this.waitMillis = waitMillis;
    }

    private static org.springframework.web.server.ResponseStatusException busy() {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "Execution capacity busy; retry with backoff");
    }

    // Denial-only guard keyed by verified subject AND untrusted selector. It never grants
    // membership, and one caller cannot burn another caller's rate budget with forged headers.
    void selectorRate(String subject, String selector) {
        synchronized (selectorWindows) {
            if (selector == null) return;
            UUID id;
            try {
                id = UUID.fromString(selector);
            } catch (IllegalArgumentException invalid) {
                return;
            }
            long now = System.nanoTime();
            if (selectorWindows.size() >= 4096)
                selectorWindows
                        .entrySet()
                        .removeIf(e -> now - e.getValue().last > TimeUnit.SECONDS.toNanos(2));
            var owner = selectorWindows.get(subject);
            if (owner == null) {
                if (selectorWindows.size() >= 4096) throw busy();
                owner = new SelectorWindow();
                selectorWindows.put(subject, owner);
            }
            owner.last = now;
            if (owner.selectors.size() >= 64)
                owner
                        .selectors
                        .entrySet()
                        .removeIf(e -> now - e.getValue().start() >= TimeUnit.SECONDS.toNanos(1));
            var window = owner.selectors.get(id);
            if (window == null || now - window.start() >= TimeUnit.SECONDS.toNanos(1)) {
                if (window == null && owner.selectors.size() >= 64) throw busy();
                owner.selectors.put(id, new Window(now, 1));
                return;
            }
            if (window.count() >= selectorRps)
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "Subject/selector request rate exceeded");
            owner.selectors.put(id, new Window(window.start(), window.count() + 1));
        }
    }

    private void principalRate(String subject) {
        synchronized (windows) {
            long now = System.nanoTime();
            if (now - lastSweep > TimeUnit.SECONDS.toNanos(1)) {
                windows.entrySet().removeIf(e -> now - e.getValue().start() > TimeUnit.SECONDS.toNanos(2));
                lastSweep = now;
            }
            Window window = windows.get(subject);
            if (window == null || now - window.start() >= TimeUnit.SECONDS.toNanos(1)) {
                if (window == null && windows.size() >= 4096) throw busy();
                windows.put(subject, new Window(now, 1));
                return;
            }
            if (window.count() >= principalRps)
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "Principal request rate exceeded");
            windows.put(subject, new Window(window.start(), window.count() + 1));
        }
    }

    AutoCloseable ingress(String subject) {
        synchronized (incoming) {
            if (incoming.getOrDefault(subject, 0) >= Math.max(2, capacity / 2) || !ingress.tryAcquire())
                throw busy();
            incoming.merge(subject, 1, Integer::sum);
        }
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                synchronized (incoming) {
                    int n = incoming.get(subject) - 1;
                    if (n == 0) incoming.remove(subject);
                    else incoming.put(subject, n);
                }
                ingress.release();
            }
        };
    }

    AutoCloseable acquire(UUID id) {
        return acquirePrincipal(id.toString(), perTenant);
    }

    AutoCloseable acquire(UUID id, String subject) {
        Objects.requireNonNull(id);
        principalRate(Objects.requireNonNull(subject));
        return acquirePrincipal(subject, Math.max(perTenant, capacity / 2));
    }

    AutoCloseable acquirePrincipal(String id, int limit) {
        Objects.requireNonNull(id);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        try {
            synchronized (active) {
                while (active.getOrDefault(id, 0) >= limit) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw busy();
                    TimeUnit.NANOSECONDS.timedWait(active, remaining);
                }
                active.merge(id, 1, Integer::sum);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        boolean acquired = false;
        try {
            acquired = global.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!acquired) throw busy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        } finally {
            if (!acquired) releaseTenant(id);
        }
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                global.release();
                releaseTenant(id);
            }
        };
    }

    private void releaseTenant(String id) {
        synchronized (active) {
            int left = active.get(id) - 1;
            if (left == 0) active.remove(id);
            else active.put(id, left);
            active.notifyAll();
        }
    }

    AutoCloseable verified(UUID id) {
        synchronized (verified) {
            int count = verified.getOrDefault(id, 0);
            if (count >= perTenant) throw busy();
            verified.put(id, count + 1);
        }
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                synchronized (verified) {
                    int left = verified.get(id) - 1;
                    if (left == 0) verified.remove(id);
                    else verified.put(id, left);
                }
            }
        };
    }

    private static class SelectorWindow {
        final Map<UUID, Window> selectors = new HashMap<>();
        long last;
    }

    private record Window(long start, int count) {
    }
}
