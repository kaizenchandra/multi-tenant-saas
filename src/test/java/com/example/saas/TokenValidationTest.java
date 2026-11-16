package com.example.saas;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;

class TokenValidationTest {
    @Test
    void diagnosticFilterSpansAreOptionalWhileAuthenticationObservationsRemain() {
        var defaults = new Security().observations(false);
        assertThat(defaults.shouldObserveRequests()).isFalse();
        assertThat(defaults.shouldObserveAuthentications()).isTrue();
        assertThat(defaults.shouldObserveAuthorizations()).isTrue();
        assertThat(new Security().observations(true).shouldObserveRequests()).isTrue();
    }

    @Test
    void realSignatureValidationAlsoRequiresExpiryIssuerAndAudience() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("local-verification").generate();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/jwks",
                exchange -> {
                    byte[] data =
                            ("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var decoder =
                    new Security()
                            .decoder(
                                    "https://issuer.example.test",
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
                                    "saas-api");
            for (String variant :
                    java.util.List.of("valid", "no-expiry", "wrong-audience", "wrong-issuer", "expired")) {
                var claims =
                        new JWTClaimsSet.Builder()
                                .subject("local-user")
                                .issuer(
                                        variant.equals("wrong-issuer")
                                                ? "https://other.example.test"
                                                : "https://issuer.example.test")
                                .audience(variant.equals("wrong-audience") ? "other-api" : "saas-api")
                                .issueTime(Date.from(Instant.now().minusSeconds(300)));
                if (!variant.equals("no-expiry"))
                    claims.expirationTime(
                            Date.from(Instant.now().plusSeconds(variant.equals("expired") ? -120 : 60)));
                var signed =
                        new SignedJWT(
                                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                                claims.build());
                signed.sign(new RSASSASigner(key));
                String token = signed.serialize();
                if (variant.equals("valid"))
                    assertThat(decoder.decode(token).getSubject()).isEqualTo("local-user");
                else
                    assertThatThrownBy(() -> decoder.decode(token))
                            .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
            }
        } finally {
            server.stop(0);
        }
    }
}
