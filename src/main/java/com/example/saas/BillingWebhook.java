package com.example.saas;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class BillingWebhook {
    final String secret;
    final JdbcTemplate db;
    final ObjectMapper json;
    final TransactionTemplate tx;
    final TenantTransactions tenant;

    BillingWebhook(
            @Value("${saas.billing-secret}") String secret,
            JdbcTemplate db,
            ObjectMapper json,
            PlatformTransactionManager m,
            TenantTransactions tenant) {
        this.secret = secret;
        this.tenant = tenant;
        this.db = db;
        this.json = json;
        tx = new TransactionTemplate(m);
    }

    @PostMapping("/api/v1/billing/webhook")
    Object webhook(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Signature") String sig,
            @RequestHeader("X-Timestamp") long timestamp,
            @RequestBody String body)
            throws Exception {
        ControlPlane.platform(j);
        if (secret.length() < 32
                || Math.abs(java.time.Instant.now().getEpochSecond() - timestamp) > 300)
            throw ApiError.forbidden();
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(sig))) throw ApiError.forbidden();
        var b = json.readTree(body);
        UUID t = UUID.fromString(b.path("tenantId").asText());
        String state = b.path("status").asText();
        if (!Set.of("TRIALING", "ACTIVE", "PAST_DUE", "SUSPENDED", "CANCELLED").contains(state))
            throw new IllegalArgumentException();
        String id = b.path("eventId").asText();
        if (id.isBlank() || id.length() > 200) throw new IllegalArgumentException();

        long occurred = b.has("occurredAt") ? b.path("occurredAt").asLong(-1) : timestamp;
        if (occurred < 0 || occurred > java.time.Instant.now().getEpochSecond() + 60)
            throw new IllegalArgumentException();
        String fingerprint =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                (t
                                                        + "|"
                                                        + state
                                                        + "|"
                                                        + (b.has("occurredAt") ? String.valueOf(occurred) : "unspecified")
                                                        + "|"
                                                        + id)
                                                        .getBytes(StandardCharsets.UTF_8)));
        return tx.execute(
                s -> {
                    tenant.fence(t, true);
                    db.queryForMap("select id from tenants where id=? for update", t);
                    int inserted =
                            db.update(
                                    "insert into billing_events(id,tenant_id,fingerprint,occurred_at)"
                                            + " values(?,?,?,to_timestamp(?)) on conflict do nothing",
                                    id,
                                    t,
                                    fingerprint,
                                    occurred);
                    if (inserted == 0) {
                        var previous =
                                db.queryForMap("select tenant_id,fingerprint from billing_events where id=?", id);
                        if (!t.equals(previous.get("tenant_id"))
                                || !fingerprint.equals(previous.get("fingerprint")))
                            throw ApiError.conflict("Conflicting billing event replay");
                        return Map.of("received", true, "duplicate", true);
                    }
                    db.queryForObject(
                            "select set_config('app.current_tenant',?,true)", String.class, t.toString());
                    int applied =
                            db.update(
                                    "update tenants set subscription=?,billing_event_at=to_timestamp(?) where id=?"
                                            + " and (billing_event_at is null or billing_event_at<to_timestamp(?))",
                                    state,
                                    occurred,
                                    t,
                                    occurred);
                    if (applied == 1)
                        db.update(
                                "insert into billing_history select ?,?,?,plan,?,now() from tenants where id=?",
                                t,
                                UUID.randomUUID(),
                                state,
                                j.getSubject(),
                                t);
                    db.update(
                            "insert into platform_audit(tenant_id,actor,action) values(?,?,?)",
                            t,
                            j.getSubject(),
                            applied == 1 ? "BILLING_WEBHOOK" : "STALE_BILLING_EVENT_IGNORED");
                    return Map.of("received", true, "applied", applied == 1);
                });
    }
}
