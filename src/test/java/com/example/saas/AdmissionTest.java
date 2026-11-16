package com.example.saas;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;

class AdmissionTest {
    @Test
    void concurrentLanesRemainBoundedAndReleaseAllPermits() throws Exception {
        var gate = new Admission(8, 2, 0);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var tenants = java.util.stream.IntStream.range(0, 4).mapToObj(i -> UUID.randomUUID()).toList();
        var perTenant = new ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicInteger>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            var calls = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 400; i++) {
                int index = i;
                calls.add(
                        pool.submit(
                                () -> {
                                    UUID tenant = tenants.get(index % tenants.size());
                                    String actor = "actor-" + index % 16;
                                    gate.selectorRate(actor, tenant.toString());
                                    try (var ingress = gate.ingress(actor);
                                         var permit = gate.acquire(tenant, actor);
                                         var verified = gate.verified(tenant)) {
                                        int local =
                                                perTenant
                                                        .computeIfAbsent(
                                                                tenant, t -> new java.util.concurrent.atomic.AtomicInteger())
                                                        .incrementAndGet();
                                        int total = active.incrementAndGet();
                                        try {
                                            maximum.accumulateAndGet(total, Math::max);
                                            assertThat(local).isLessThanOrEqualTo(2);
                                            assertThat(total).isLessThanOrEqualTo(8);
                                            Thread.yield();
                                        } finally {
                                            active.decrementAndGet();
                                            perTenant.get(tenant).decrementAndGet();
                                        }
                                    } catch (org.springframework.web.server.ResponseStatusException limited) {
                                        assertThat(limited.getStatusCode().value()).isEqualTo(429);
                                    } catch (Exception error) {
                                        throw new RuntimeException(error);
                                    }
                                }));
            }
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
        }
        assertThat(maximum.get()).isBetween(1, 8);
        assertThat(active.get()).isZero();
        var permits = new java.util.ArrayList<AutoCloseable>();
        try {
            for (int i = 0; i < 8; i++) permits.add(gate.acquire(UUID.randomUUID(), "final-" + i));
            assertThatThrownBy(() -> gate.acquire(UUID.randomUUID(), "overflow"))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        } finally {
            for (var permit : permits) permit.close();
        }
    }

    @Test
    void principalBurstWaitsForReleaseWithinBound() throws Exception {
        var admission = new Admission(4, 1, 500);
        var first = admission.acquirePrincipal("alice", 1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var started = new CountDownLatch(1);
            var waiting =
                    executor.submit(
                            () -> {
                                started.countDown();
                                try (var permit = admission.acquirePrincipal("alice", 1)) {
                                    return true;
                                }
                            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiting.get(30, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            try (var other = admission.acquire(UUID.randomUUID(), "bob")) {
                assertThat(other).isNotNull();
            }
            first.close();
            assertThat(waiting.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            first.close();
        }
    }

    @Test
    void fullPrincipalQueueTimesOutAndDoesNotLeakPermit() throws Exception {
        var admission = new Admission(2, 1, 20);
        try (var first = admission.acquirePrincipal("alice", 1)) {
            assertThatThrownBy(() -> admission.acquirePrincipal("alice", 1))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        try (var retry = admission.acquirePrincipal("alice", 1)) {
            assertThat(retry).isNotNull();
        }
    }

    @Test
    void principalRateRejectionLeavesUnrelatedCapacityAvailable() throws Exception {
        var gate = new Admission(4, 1, 0);
        gate.principalRps = 2;
        var t = UUID.randomUUID();
        for (int i = 0; i < 2; i++)
            try (var permit = gate.acquire(t, "hot")) {
            }
        assertThatThrownBy(() -> gate.acquire(t, "hot"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Principal request rate");
        try (var neighbor = gate.acquire(t, "neighbor")) {
            assertThat(neighbor).isNotNull();
        }
    }

    @Test
    void forgedSelectorsOnlyThrottleTheAuthenticatedCallersOwnPair() {
        var gate = new Admission(4, 1, 0);
        gate.selectorRps = 2;
        String tenant = UUID.randomUUID().toString();
        gate.selectorRate("attacker", tenant);
        gate.selectorRate("attacker", tenant);
        assertThatThrownBy(() -> gate.selectorRate("attacker", tenant))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatCode(() -> gate.selectorRate("owner", tenant)).doesNotThrowAnyException();
        assertThatCode(() -> gate.selectorRate("attacker", UUID.randomUUID().toString()))
                .doesNotThrowAnyException();
    }
}
