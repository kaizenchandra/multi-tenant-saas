package com.example.saas;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class Mutations {
    final JdbcTemplate db;
    final ObjectMapper json;
    final com.fasterxml.jackson.databind.ObjectWriter canonical;

    Mutations(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
        this.canonical =
                json.copy()
                        .configure(
                                com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                        .configure(
                                com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                        .writer();
    }

    private static String digest(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    Object once(TenantContext c, String key, String route, Object request, Supplier<Object> work) {
        if (key == null) return work.get();
        if (!key.matches("[A-Za-z0-9_-]{8,100}")) throw new IllegalArgumentException();
        try {
            var envelope = new TreeMap<String, Object>();
            envelope.put("route", route);
            envelope.put("body", request);
            String hash = digest(canonical.writeValueAsBytes(envelope));
            db.update("delete from idempotency where id=? and expires_at<=now()", key);
            var old =
                    db.queryForList(
                            "select actor,request_hash,response::text as response from idempotency where id=?",
                            key);
            if (!old.isEmpty()) {
                var row = old.getFirst();
                var legacyOne = new LinkedHashMap<String, Object>();
                legacyOne.put("route", route);
                legacyOne.put("body", request);
                var legacyTwo = new LinkedHashMap<String, Object>();
                legacyTwo.put("body", request);
                legacyTwo.put("route", route);
                boolean matches =
                        hash.equals(row.get("request_hash"))
                                || digest(json.writeValueAsBytes(legacyOne)).equals(row.get("request_hash"))
                                || digest(json.writeValueAsBytes(legacyTwo)).equals(row.get("request_hash"));
                if (!c.actor().equals(row.get("actor")) || !matches)
                    throw ApiError.conflict("Idempotency key reused with different operation");
                return json.readTree((String) row.get("response"));
            }
            Object response = work.get();
            db.update(
                    "insert into idempotency(tenant_id,id,actor,request_hash,response)"
                            + " values(?,?,?,?,?::jsonb)",
                    c.tenantId(),
                    key,
                    c.actor(),
                    hash,
                    json.writeValueAsString(response));
            return response;
        } catch (org.springframework.web.server.ResponseStatusException
                 | org.springframework.dao.DataAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode mutation", e);
        }
    }
}
