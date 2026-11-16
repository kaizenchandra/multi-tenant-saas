package com.example.saas;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A learned denial gate. Never returns a principal, membership or authentication success.
 */
@Component
class VerifiedTrafficGuard {
    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();
    private final Semaphore slots;
    private final int limit;
    private final LongSupplier clock;
    private final Counter rejected;

    @Autowired
    VerifiedTrafficGuard(
            @Value("${saas.admission.selector-rps:60}") int limit,
            @Value("${saas.admission.known-pairs:4096}") int capacity,
            MeterRegistry metrics) {
        this(limit, capacity, metrics, System::nanoTime);
    }

    VerifiedTrafficGuard(int limit, int capacity, MeterRegistry metrics, LongSupplier clock) {
        if (limit < 1 || capacity < 1 || capacity > 65536)
            throw new IllegalArgumentException("Invalid learned denial limits");
        this.limit = limit;
        this.clock = clock;
        this.slots = new Semaphore(capacity);
        rejected = metrics.counter("saas.ingress.pre_auth.rejected");
    }

    private Key key(String token, String selector) {
        if (token == null || selector == null) return null;
        UUID tenant;
        try {
            tenant = UUID.fromString(selector);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.UTF_8)));
            return new Key(hash, tenant);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    boolean reject(String authorization, String selector) {
        if (authorization == null
                || authorization.length() < 8
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        Key key = key(authorization.substring(7), selector);
        if (key == null) return false;
        Window window = windows.get(key);
        if (window != null
                && clock.getAsLong() - window.start() < TimeUnit.SECONDS.toNanos(1)
                && window.count() >= limit) {
            rejected.increment();
            return true;
        }
        return false;
    }

    // Call only AFTER successful JWT authentication and the subject/selector rate guard.
    void authenticated(String verifiedToken, String selector) {
        Key key = key(verifiedToken, selector);
        if (key == null) return;
        long now = clock.getAsLong();
        windows.compute(
                key,
                (ignored, old) -> {
                    if (old == null && !slots.tryAcquire()) return null;
                    if (old == null || now - old.start() >= TimeUnit.SECONDS.toNanos(1))
                        return new Window(now, 1, now);
                    return new Window(old.start(), Math.min(limit, old.count() + 1), now);
                });
    }

    @Scheduled(fixedDelay = 1000)
    void clean() {
        long now = clock.getAsLong();
        windows.forEach(
                (key, window) -> {
                    if (now - window.verifiedAt() > TimeUnit.SECONDS.toNanos(2)
                            && windows.remove(key, window)) slots.release();
                });
    }

    int cachedPairs() {
        return windows.size();
    }

    private record Key(String fingerprint, UUID selector) {
    }

    private record Window(long start, int count, long verifiedAt) {
    }
}
