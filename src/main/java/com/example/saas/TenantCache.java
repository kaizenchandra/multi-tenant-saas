package com.example.saas;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class TenantCache {
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> PUT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local old=redis.call('ZRANGE',KEYS[1],0,-1); for _,k in ipairs(old) do if"
                            + " redis.call('EXISTS',k)==0 then redis.call('ZREM',KEYS[1],k) end end;"
                            + " redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[2]); local tm=redis.call('TIME');"
                            + " redis.call('ZADD',KEYS[1],tm[1]*1000000+tm[2],KEYS[2]); while"
                            + " redis.call('ZCARD',KEYS[1])>tonumber(ARGV[3]) do local"
                            + " victim=redis.call('ZPOPMIN',KEYS[1],1); redis.call('DEL',victim[1]); end;"
                            + " redis.call('EXPIRE',KEYS[1],tonumber(ARGV[2])+1); return 1",
                    Long.class);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> CLEAR =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local entries=redis.call('ZRANGE',KEYS[1],0,-1); for _,k in ipairs(entries) do"
                            + " redis.call('DEL',k) end; redis.call('DEL',KEYS[1]); return #entries",
                    Long.class);
    final StringRedisTemplate redis;
    final ObjectMapper json;
    final MeterRegistry metrics;
    @org.springframework.beans.factory.annotation.Value("${saas.cache.max-entries-per-tenant:100}")
    int maxEntries = 100;
    @org.springframework.beans.factory.annotation.Value("${saas.cache.ttl-seconds:30}")
    int ttl = 30;

    TenantCache(StringRedisTemplate redis, ObjectMapper json, MeterRegistry metrics) {
        this.redis = redis;
        this.json = json;
        this.metrics = metrics;
    }

    static String key(UUID tenant, UUID id, Object version) {
        return "tenant:{"
                + Objects.requireNonNull(tenant)
                + "}:project:"
                + Objects.requireNonNull(id)
                + ":v:"
                + version;
    }

    void invalidate(UUID tenant) {
        try {
            redis.execute(CLEAR, List.of("tenant:{" + tenant + "}:cache-index"));
        } catch (Exception e) {
            metrics.counter("saas.cache", "result", "unavailable").increment();
        }
    }

    Object readThrough(TenantContext c, UUID id, Map<String, Object> authoritative) {
        // Version is read under RLS first, avoiding stale cache resurrection after update/delete.
        String key = key(c.tenantId(), id, authoritative.get("version"));
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                var envelope = json.readTree(cached);
                if (c.tenantId().toString().equals(envelope.path("tenant").asText())
                        && id.toString().equals(envelope.path("data").path("id").asText())) {
                    metrics.counter("saas.cache", "result", "hit").increment();
                    return envelope.get("data");
                }
            }
            redis.execute(
                    PUT,
                    List.of("tenant:{" + c.tenantId() + "}:cache-index", key),
                    json.writeValueAsString(Map.of("tenant", c.tenantId(), "data", authoritative)),
                    String.valueOf(ttl),
                    String.valueOf(maxEntries));
            metrics.counter("saas.cache", "result", "miss").increment();
        } catch (Exception e) {
            metrics.counter("saas.cache", "result", "unavailable").increment();
        }
        return authoritative;
    }
}
