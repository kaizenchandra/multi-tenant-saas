package com.example.saas;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisIsolationTest {
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.6-alpine")).withExposedPorts(6379);

    @Test
    void boundedTenantNamespaceInvalidationAndActualOutage() {
        var config = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        var factory =
                new LettuceConnectionFactory(
                        config,
                        LettuceClientConfiguration.builder()
                                .commandTimeout(Duration.ofMillis(200))
                                .shutdownTimeout(Duration.ZERO)
                                .build());
        factory.afterPropertiesSet();
        factory.start();
        try {
            var template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            var cache = new TenantCache(template, new ObjectMapper(), new SimpleMeterRegistry());
            cache.maxEntries = 3;
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), shared = UUID.randomUUID();
            var ca = new TenantContext(a, "a", "TENANT_OWNER", UUID.randomUUID());
            var cb = new TenantContext(b, "b", "TENANT_OWNER", UUID.randomUUID());
            cache.readThrough(cb, shared, Map.of("id", shared, "version", 0, "name", "B secret"));
            for (int i = 0; i < 10; i++) {
                UUID id = UUID.randomUUID();
                cache.readThrough(ca, id, Map.of("id", id, "version", 0, "name", "A"));
            }
            assertThat(template.opsForZSet().size("tenant:{" + a + "}:cache-index")).isEqualTo(3);
            assertThat(
                    cache
                            .readThrough(ca, shared, Map.of("id", shared, "version", 0, "name", "A public"))
                            .toString())
                    .doesNotContain("B secret");
            cache.invalidate(a);
            assertThat(template.hasKey(TenantCache.key(b, shared, 0))).isTrue();
            assertThat(template.opsForZSet().size("tenant:{" + a + "}:cache-index")).isZero();
            redis.stop();
            Map<String, Object> safe = Map.of("id", shared, "version", 0, "name", "safe fallback");
            assertThat(cache.readThrough(ca, shared, safe)).isEqualTo(safe);
        } finally {
            factory.destroy();
        }
    }
}
