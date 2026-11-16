package com.example.saas;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class QueryCacheTest {
    @Test
    void epochsAndTenantIdentityArePartOfEveryKey() {
        var cache = new QueryCache(new SimpleMeterRegistry());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var ca = new TenantContext(a, "actor", "TENANT_OWNER", UUID.randomUUID(), 1);
        var cb = new TenantContext(b, "actor", "TENANT_OWNER", UUID.randomUUID(), 1);
        var calls = new AtomicInteger();
        assertThat(
                cache.list(
                        ca,
                        "",
                        0,
                        25,
                        () -> {
                            calls.incrementAndGet();
                            return Map.of("name", "A");
                        }))
                .isEqualTo(Map.of("name", "A"));
        cache.list(
                ca,
                "",
                0,
                25,
                () -> {
                    throw new AssertionError("Cache miss");
                });
        assertThat(cache.list(cb, "", 0, 25, () -> Map.of("name", "B"))).isEqualTo(Map.of("name", "B"));
        var next = new TenantContext(a, "actor", "TENANT_OWNER", UUID.randomUUID(), 2);
        assertThat(
                cache.list(
                        next,
                        "",
                        0,
                        25,
                        () -> {
                            calls.incrementAndGet();
                            return Map.of("name", "updated");
                        }))
                .isEqualTo(Map.of("name", "updated"));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void concurrentMissesCoalesceWithoutHoldingTheCacheMonitor() throws Exception {
        var registry = new SimpleMeterRegistry();
        var cache = new QueryCache(registry);
        var context =
                new TenantContext(UUID.randomUUID(), "actor", "TENANT_OWNER", UUID.randomUUID(), 1);
        var starts = new java.util.concurrent.CyclicBarrier(4);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var loads = new AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    starts.await();
                                    return cache.list(
                                            context,
                                            "",
                                            0,
                                            25,
                                            () -> {
                                                loads.incrementAndGet();
                                                entered.countDown();
                                                try {
                                                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                                return Map.of("id", 1);
                                            });
                                }));
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                org.awaitility.Awaitility.await()
                        .atMost(java.time.Duration.ofMillis(400))
                        .until(
                                () ->
                                        registry.find("saas.query.cache").tag("result", "coalesced").counter() != null
                                                && registry
                                                .find("saas.query.cache")
                                                .tag("result", "coalesced")
                                                .counter()
                                                .count()
                                                == 3);
            } finally {
                release.countDown();
            }
            for (var future : futures)
                assertThat(future.get(1, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(Map.of("id", 1));
            assertThat(loads.get()).isEqualTo(1);
        }
    }
}
