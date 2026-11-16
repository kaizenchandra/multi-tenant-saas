package com.example.saas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
    static void problem(jakarta.servlet.http.HttpServletResponse response, int status)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response
                .getWriter()
                .write("{\"type\":\"about:blank\",\"title\":\"Request denied\",\"status\":" + status + "}");
    }

    static boolean isPlatform(Jwt j) {
        try {
            ControlPlane.platform(j);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return false;
        }
    }

    @Bean
    org.springframework.security.config.observation.SecurityObservationSettings observations(
            @Value("${saas.observability.security-filter-spans:false}") boolean filterSpans) {
        // Spring Security 6.5 calls this shouldObserveRequests. HTTP server observations
        // still cover each request; retain authn/authz without dozens of filter spans.
        return org.springframework.security.config.observation.SecurityObservationSettings
                .withDefaults()
                .shouldObserveRequests(filterSpans)
                .build();
    }

    @Bean
    JwtDecoder decoder(
            @Value("${saas.issuer}") String issuer,
            @Value("${saas.jwk-uri}") String jwks,
            @Value("${saas.audience}") String audience) {
        var d = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        d.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        jwt ->
                                jwt.getExpiresAt() != null
                                        && jwt.getSubject() != null
                                        && !jwt.getSubject().isBlank()
                                        && jwt.getAudience() != null
                                        && jwt.getAudience().contains(audience)
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return d;
    }

    @Bean
    SecurityFilterChain chain(
            HttpSecurity h,
            @Value("${saas.issuer}") String issuer,
            Admission admission,
            VerifiedTrafficGuard traffic)
            throws Exception {
        return h.addFilterBefore(
                        new org.springframework.web.filter.OncePerRequestFilter() {
                            protected void doFilterInternal(
                                    jakarta.servlet.http.HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response,
                                    jakarta.servlet.FilterChain chain)
                                    throws jakarta.servlet.ServletException, java.io.IOException {
                                if (request.getRequestURI().startsWith("/api/v1/")
                                        && traffic.reject(
                                        request.getHeader("Authorization"), request.getHeader("X-Tenant-ID"))) {
                                    response.setStatus(429);
                                    response.setContentType("application/problem+json");
                                    response.setHeader("Retry-After", "1");
                                    response
                                            .getWriter()
                                            .write(
                                                    "{\"type\":\"about:blank\",\"status\":429,\"detail\":\"Known"
                                                            + " token/selector request rate exceeded\"}");
                                    return;
                                }
                                chain.doFilter(request, response);
                            }
                        },
                        org.springframework.security.oauth2.server.resource.web.authentication
                                .BearerTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new org.springframework.web.filter.OncePerRequestFilter() {
                            protected void doFilterInternal(
                                    jakarta.servlet.http.HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response,
                                    jakarta.servlet.FilterChain chain)
                                    throws jakarta.servlet.ServletException, java.io.IOException {
                                var authentication =
                                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                                .getAuthentication();
                                if (!request.getRequestURI().startsWith("/api/v1/")
                                        || authentication == null
                                        || !(authentication.getPrincipal() instanceof Jwt jwt)) {
                                    chain.doFilter(request, response);
                                    return;
                                }
                                AutoCloseable permit;
                                try {
                                    admission.selectorRate(jwt.getSubject(), request.getHeader("X-Tenant-ID"));
                                    traffic.authenticated(jwt.getTokenValue(), request.getHeader("X-Tenant-ID"));
                                    permit = admission.ingress(jwt.getSubject());
                                } catch (org.springframework.web.server.ResponseStatusException busy) {
                                    response.setStatus(429);
                                    response.setHeader("Retry-After", "1");
                                    response.setContentType("application/problem+json");
                                    response
                                            .getWriter()
                                            .write(
                                                    "{\"type\":\"about:blank\",\"status\":429,\"detail\":\""
                                                            + ("Subject/selector request rate exceeded".equals(busy.getReason())
                                                            ? "Subject/selector request rate exceeded"
                                                            : "Execution capacity busy; retry with backoff")
                                                            + "\"}");
                                    return;
                                }
                                try {
                                    chain.doFilter(request, response);
                                } finally {
                                    try {
                                        permit.close();
                                    } catch (Exception impossible) {
                                        throw new IllegalStateException(impossible);
                                    }
                                }
                            }
                        },
                        org.springframework.security.oauth2.server.resource.web.authentication
                                .BearerTokenAuthenticationFilter.class)
                .csrf(c -> c.disable())
                .sessionManagement(
                        s ->
                                s.sessionCreationPolicy(
                                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/actuator/prometheus")
                                        .access(
                                                (authentication, request) ->
                                                        new org.springframework.security.authorization.AuthorizationDecision(
                                                                authentication.get().getPrincipal() instanceof Jwt j
                                                                        && isPlatform(j)))
                                        .requestMatchers(
                                                "/",
                                                "/ui-config",
                                                "/index.html",
                                                "/app.js",
                                                "/style.css",
                                                "/actuator/health/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e -> e.accessDeniedHandler((request, response, error) -> problem(response, 403)))
                .oauth2ResourceServer(
                        o ->
                                o.jwt(j -> {
                                        })
                                        .authenticationEntryPoint((request, response, error) -> problem(response, 401))
                                        .accessDeniedHandler((request, response, error) -> problem(response, 403)))
                .headers(
                        x ->
                                x.contentSecurityPolicy(
                                        c ->
                                                c.policyDirectives(
                                                        "default-src 'self'; connect-src 'self' "
                                                                + java.net.URI.create(issuer).resolve("/").toString()
                                                                + "; frame-ancestors 'none'; base-uri 'self'")))
                .build();
    }
}
