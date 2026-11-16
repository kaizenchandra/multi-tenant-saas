package com.example.saas;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

@Component
@ConditionalOnProperty(name = "saas.messaging", havingValue = "true")
class EventConsumer {
    final TenantTransactions tenant;
    final JdbcTemplate db;
    final ObjectMapper json;

    EventConsumer(TenantTransactions t, JdbcTemplate db, ObjectMapper j) {
        this.tenant = t;
        this.db = db;
        this.json = j;
    }

    @KafkaListener(topics = "saas.events")
    void accept(String payload) throws Exception {
        var e = json.readTree(payload);
        UUID id = UUID.fromString(e.path("id").asText());
        UUID t = UUID.fromString(e.path("tenant_id").asText());
        tenant.delivery(
                t,
                c -> {
                    // Treat the broker as transport, not authority: only locally committed outbox envelopes
                    // can execute.
                    var source =
                            db.queryForList(
                                    "select aggregate_id,type,version,correlation from outbox where id=?", id);
                    if (source.isEmpty()) throw ApiError.forbidden();
                    var row = source.getFirst();
                    for (String field : List.of("aggregate_id", "type", "version", "correlation"))
                        if (!row.get(field).toString().equals(e.path(field).asText()))
                            throw ApiError.forbidden();
                    db.update(
                            "insert into inbox(tenant_id,id,processed) values(?,?,false) on conflict do nothing",
                            t,
                            id);
                    if (Boolean.TRUE.equals(
                            db.queryForObject("select state='ACTIVE' from tenants where id=?", Boolean.class, t)))
                        process(c, id);
                    return null;
                });
    }

    void process(TenantContext c, UUID event) {
        if (db.update("update inbox set processed=true where id=? and not processed", event) == 1) {
            db.update(
                    "insert into event_projection(tenant_id,id,events) select tenant_id,type,1 from outbox"
                            + " where id=? on conflict(tenant_id,id) do update set"
                            + " events=event_projection.events+1",
                    event);
            tenant.audit(c, "EVENT_CONSUMED", event);
        }
    }
}

@Configuration
class KafkaErrors {
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        return new DefaultErrorHandler(
                (record, ex) -> {
                    try {
                        kafka
                                .send(
                                        "saas.events.DLT", String.valueOf(record.key()), String.valueOf(record.value()))
                                .get(3, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("DLT unavailable", e);
                    }
                },
                new FixedBackOff(1000, 2));
    }
}
