package com.example.saas;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionGuardTest {
    MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("saas.issuer", "https://identity.example.test/realm")
                .withProperty("saas.jwk-uri", "https://identity.example.test/jwks")
                .withProperty(
                        "spring.datasource.url", "jdbc:postgresql://db.example.test/saas?sslmode=verify-full")
                .withProperty("spring.datasource.password", "runtime-secret")
                .withProperty("spring.data.redis.password", "cache-secret")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.data.redis.ssl.enabled", "true")
                .withProperty("spring.kafka.properties.security.protocol", "SASL_SSL")
                .withProperty("saas.billing.sandbox-enabled", "false");
    }

    @Test
    void configuredSecureBoundaryIsAccepted() {
        ProductionGuard.validate(valid());
    }

    @Test
    void plaintextAndDemoOverridesFailClosed() {
        for (String[] override :
                new String[][]{
                        {"saas.issuer", "http://identity.example.test"},
                        {
                                "spring.datasource.url", "jdbc:postgresql://db/saas?sslmode=verify-full&sslmode=disable"
                        },
                        {"spring.data.redis.url", "redis://cache"},
                        {"spring.datasource.password", "local-runtime-only"},
                        {"spring.kafka.consumer.properties.security.protocol", "PLAINTEXT"},
                        {"spring.flyway.enabled", "true"},
                        {"saas.billing.sandbox-enabled", "true"}
                }) {
            var env = valid().withProperty(override[0], override[1]);
            assertThatThrownBy(() -> ProductionGuard.validate(env))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @org.junit.jupiter.api.Test
    void rejectsInsecureTransportBeforeOrdinaryBeansAreCreated() {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        var environment =
                valid().withProperty("spring.datasource.url", "jdbc:postgresql://db/saas?sslmode=disable");
        environment.setActiveProfiles("production");
        context.setEnvironment(environment);
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        context.register(ProductionGuard.class);
        context.registerBean(
                "sensitiveConnection",
                Object.class,
                () -> {
                    created.set(true);
                    return new Object();
                });
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(context::refresh)
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThat(created.get()).isFalse();
        } finally {
            context.close();
        }
    }
}
