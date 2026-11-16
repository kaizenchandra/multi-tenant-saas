package com.example.saas;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Authorization and the authoritative database epoch are resolved before every access.
 */
@Component
class QueryCache {
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(64, .75f, true);
    private final Map<Key, CompletableFuture<Object>> loading = new HashMap<>();
    private final MeterRegistry metrics;
    @Value("${saas.query-cache.ttl-seconds:30}")
    int ttlSeconds = 30;

    QueryCache(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    Object list(TenantContext context, String query, int page, int size, Supplier<Object> loader) {
        var key = new Key(context.tenantId(), context.dataEpoch(), query, page, size);
        CompletableFuture<Object> future;
        boolean owner;
        synchronized (this) {
            var found = entries.get(key);
            if (found != null && System.nanoTime() < found.expires()) {
                metrics.counter("saas.query.cache", "result", "hit").increment();
                return found.result();
            }
            future = loading.get(key);
            owner = future == null;
            if (owner) {
                future = new CompletableFuture<>();
                loading.put(key, future);
            }
        }
        if (!owner) {
            try {
                metrics.counter("saas.query.cache", "result", "coalesced").increment();
                return future.get(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted cache load", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                throw new IllegalStateException(e.getCause());
            } catch (TimeoutException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Query load is busy");
            }
        }
        try {
            Object value = loader.get();
            synchronized (this) {
                entries.put(
                        key, new Entry(value, System.nanoTime() + Duration.ofSeconds(ttlSeconds).toNanos()));
                while (entries.size() > 1000) entries.remove(entries.keySet().iterator().next());
            }
            future.complete(value);
            metrics.counter("saas.query.cache", "result", "miss").increment();
            return value;
        } catch (RuntimeException | Error e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (this) {
                loading.remove(key);
            }
        }
    }

    record Key(UUID tenant, long epoch, String query, int page, int size) {
    }

    record Entry(Object result, long expires) {
    }
}
