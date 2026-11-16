package com.example.saas;

import java.net.URI;
import java.util.*;

import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
class ProductionGuard
        implements org.springframework.beans.factory.config.BeanFactoryPostProcessor,
        org.springframework.context.EnvironmentAware {
    Environment env;

    static void validate(Environment env) {
        for (String key : List.of("saas.issuer", "saas.jwk-uri")) {
            URI uri = URI.create(env.getRequiredProperty(key));
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null)
                throw new IllegalStateException("Production identity endpoints require HTTPS");
        }
        String url = env.getRequiredProperty("spring.datasource.url");
        int query = url.indexOf('?');
        if (query < 0) throw new IllegalStateException("Production DB requires verify-full TLS");
        Map<String, String> options = new HashMap<>();
        for (String pair : url.substring(query + 1).split("&")) {
            String[] fields = pair.split("=", 2);
            if (options.put(fields[0], fields.length == 2 ? fields[1] : "") != null)
                throw new IllegalStateException("Duplicate DB connection option");
        }
        if (!"verify-full".equals(options.get("sslmode")))
            throw new IllegalStateException("Production DB requires verify-full TLS");
        if (env.getProperty("spring.flyway.enabled", Boolean.class, true))
            throw new IllegalStateException("Run production migrations outside application pods");
        for (String key : List.of("spring.datasource.password", "spring.data.redis.password")) {
            String secret = env.getRequiredProperty(key);
            if (secret.isBlank() || secret.startsWith("local-") || secret.equals("demo-password"))
                throw new IllegalStateException("Production credentials are missing or demo values");
        }
        if (!env.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false))
            throw new IllegalStateException("Production Redis requires TLS");
        String redis = env.getProperty("spring.data.redis.url");
        if (redis != null && !redis.startsWith("rediss://"))
            throw new IllegalStateException("Production Redis URL requires TLS");
        for (String prefix :
                List.of(
                        "spring.kafka.properties.",
                        "spring.kafka.consumer.properties.",
                        "spring.kafka.producer.properties.")) {
            String protocol = env.getProperty(prefix + "security.protocol");
            if ((prefix.equals("spring.kafka.properties.") || protocol != null)
                    && !"SASL_SSL".equals(protocol))
                throw new IllegalStateException("Production Kafka requires SASL_SSL");
        }
        if (env.getProperty("saas.billing.sandbox-enabled", Boolean.class, true))
            throw new IllegalStateException("Production sandbox plan activation must be disabled");
    }

    public void setEnvironment(Environment env) {
        this.env = env;
    }

    public void postProcessBeanFactory(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
        validate(env);
    }
}
