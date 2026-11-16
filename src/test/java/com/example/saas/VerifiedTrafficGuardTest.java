package com.example.saas;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class VerifiedTrafficGuardTest {
    @Test
    void unknownAndModifiedTokensAlwaysContinueToNormalAuthentication() {
        var guard = new VerifiedTrafficGuard(2, 16, new SimpleMeterRegistry());
        String tenant = UUID.randomUUID().toString();
        for (int i = 0; i < 100; i++) assertThat(guard.reject("Bearer unknown", tenant)).isFalse();
        assertThat(guard.cachedPairs()).isZero();
        guard.authenticated("verified", tenant);
        guard.authenticated("verified", tenant);
        assertThat(guard.reject("Bearer verified", tenant)).isTrue();
        assertThat(guard.reject("Bearer verified-forged", tenant)).isFalse();
        assertThat(guard.reject("Bearer other-user", tenant)).isFalse();
        assertThat(guard.reject("Bearer verified", UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void expiredWindowsContinueToAuthenticationAndIdleEntriesReleaseCapacity() {
        var now = new AtomicLong();
        var guard = new VerifiedTrafficGuard(1, 1, new SimpleMeterRegistry(), now::get);
        String tenant = UUID.randomUUID().toString();
        guard.authenticated("first", tenant);
        assertThat(guard.reject("Bearer first", tenant)).isTrue();
        guard.authenticated("second", tenant);
        assertThat(guard.reject("Bearer second", tenant))
                .isFalse(); // full cache falls back to verification
        now.set(TimeUnit.SECONDS.toNanos(1));
        assertThat(guard.reject("Bearer first", tenant)).isFalse();
        now.set(TimeUnit.SECONDS.toNanos(3));
        guard.clean();
        assertThat(guard.cachedPairs()).isZero();
        guard.authenticated("second", tenant);
        assertThat(guard.reject("Bearer second", tenant)).isTrue();
    }

    @Test
    void concurrentRegistrationCannotExceedTheMemoryBound() throws Exception {
        var guard = new VerifiedTrafficGuard(2, 8, new SimpleMeterRegistry());
        String tenant = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(16)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) {
                String token = "token-" + i;
                jobs.add(pool.submit(() -> guard.authenticated(token, tenant)));
            }
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        }
        assertThat(guard.cachedPairs()).isEqualTo(8);
    }

    @Test
    void malformedOrMissingSelectorsAndSchemesNeverAcquireAuthority() {
        var guard = new VerifiedTrafficGuard(1, 8, new SimpleMeterRegistry());
        guard.authenticated("verified", "malformed");
        assertThat(guard.cachedPairs()).isZero();
        assertThat(guard.reject(null, null)).isFalse();
        assertThat(guard.reject("Basic abc", UUID.randomUUID().toString())).isFalse();
        assertThat(guard.reject("Bearer abc", "malformed")).isFalse();
    }
}
