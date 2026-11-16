package com.example.saas;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class QuotaReconciliation {
    final TenantTransactions tenant;
    final JdbcTemplate db;
    final com.fasterxml.jackson.databind.ObjectMapper json;

    QuotaReconciliation(
            TenantTransactions tenant,
            JdbcTemplate db,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.tenant = tenant;
        this.db = db;
        this.json = json;
    }

    @GetMapping("/admin/tenants/{id}/quota/reconciliation")
    Object preview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ControlPlane.platform(jwt);
        Object result =
                tenant.delivery(
                        id,
                        c ->
                                db.queryForMap(
                                        "select count(*) as unsettled_grants,coalesce(sum(granted-1),0) as"
                                                + " unconfirmed_credits,count(*) filter(where"
                                                + " window_at<clock_timestamp()-interval '61 seconds') as eligible_grants"
                                                + " from api_budgets where not closed"));
        if (result == null) throw ApiError.forbidden();
        return result;
    }

    @PostMapping("/admin/tenants/{id}/quota/reconciliation")
    Object settle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ControlPlane.platform(jwt);
        Object result =
                tenant.delivery(
                        id,
                        c -> {
                            try {
                                return json.readTree(
                                        db.queryForObject(
                                                "select reconcile_api_budgets(?,?,?)::text",
                                                String.class,
                                                id,
                                                jwt.getSubject(),
                                                1000));
                            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                throw new IllegalStateException(e);
                            }
                        });
        if (result == null) throw ApiError.forbidden();
        return result;
    }
}
