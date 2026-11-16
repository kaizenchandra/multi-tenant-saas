package com.example.saas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;

class CacheTest {
    @Test
    void forgedEnvelopeAndRedisOutageFallBackToAuthorizedRow() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        UUID tenant = UUID.randomUUID(), foreign = UUID.randomUUID(), id = UUID.randomUUID();
        Map<String, Object> row = Map.of("id", id, "name", "safe", "version", 0);
        when(ops.get(anyString()))
                .thenReturn(
                        String.format(
                                "{\"tenant\":\"%s\",\"data\":{\"id\":\"%s\",\"name\":\"secret\"}}", foreign, id));
        var cache = new TenantCache(redis, new ObjectMapper(), new SimpleMeterRegistry());
        var c = new TenantContext(tenant, "u", "TENANT_OWNER", UUID.randomUUID());
        assertThat(cache.readThrough(c, id, row)).isEqualTo(row);
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        assertThat(cache.readThrough(c, id, row)).isEqualTo(row);
    }
}
