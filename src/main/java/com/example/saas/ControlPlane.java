package com.example.saas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ControlPlane {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final TenantTransactions tenant;
    final TenantCache cache;

    @org.springframework.beans.factory.annotation.Value("${saas.billing.sandbox-enabled:true}")
    boolean sandbox = true;

    ControlPlane(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            TenantTransactions tenant,
            TenantCache cache) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tenant = tenant;
        this.cache = cache;
    }

    static void platform(Jwt j) {
        Map<String, Object> realm = j.getClaim("realm_access");
        if (realm == null
                || !(realm.get("roles") instanceof List<?> roles)
                || !roles.contains("PLATFORM_ADMIN")) throw ApiError.forbidden();
    }

    void log(Jwt j, UUID id, String action) {
        db.update(
                "insert into platform_audit(tenant_id,actor,action) values(?,?,?)",
                id,
                j.getSubject(),
                action);
    }

    @GetMapping("/me/tenants")
    List<Map<String, Object>> mine(@AuthenticationPrincipal Jwt j) {
        return db.queryForList(
                "select t.id,t.name,t.state,m.role from tenants t join memberships m on m.tenant_id=t.id"
                        + " where m.subject=? and m.active order by t.name",
                j.getSubject());
    }

    @PostMapping("/tenants")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> register(@AuthenticationPrincipal Jwt j, @Valid @RequestBody Registration b) {
        return tx.execute(
                s -> {
                    UUID id = UUID.randomUUID();
                    db.update(
                            "insert into tenants(id,slug,name,owner_subject,state) values(?,?,?,?,'PENDING') on"
                                    + " conflict(slug) do nothing",
                            id,
                            b.slug(),
                            b.name(),
                            j.getSubject());
                    var t =
                            db.queryForMap(
                                    "select id,owner_subject,state from tenants where slug=? for update", b.slug());
                    if (!t.get("owner_subject").equals(j.getSubject()))
                        throw ApiError.conflict("Slug unavailable");
                    id = (UUID) t.get("id");
                    log(j, id, "TENANT_REGISTERED");
                    return Map.of("id", id, "state", t.get("state"));
                });
    }

    @GetMapping("/tenants/{id}")
    Object get(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c -> db.queryForMap("select id,slug,name,state,plan,config from tenants where id=?", id));
    }

    @PatchMapping("/tenants/{id}")
    Object config(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Config b) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    db.update(
                            "update tenants set name=?,config=jsonb_build_object('exportEnabled',?) where id=?",
                            b.name(),
                            b.exportEnabled(),
                            id);
                    tenant.audit(c, "TENANT_CONFIGURED", id);
                    return Map.of("id", id);
                });
    }

    @GetMapping("/admin/tenants")
    Object all(@AuthenticationPrincipal Jwt j, @RequestParam(defaultValue = "0") int page) {
        platform(j);
        if (page < 0 || page > 100000) throw new IllegalArgumentException();
        return db.queryForList(
                "select id,name,state,plan from tenants order by id limit 100 offset ?", page * 100);
    }

    @GetMapping("/admin/tenants/{id}/health")
    Object health(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        platform(j);
        log(j, id, "HEALTH_INSPECTED");
        return db.queryForMap("select id,state,plan,created_at from tenants where id=?", id);
    }

    @PostMapping("/admin/tenants/{id}/provision")
    Object provision(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        platform(j);
        return tx.execute(
                s -> {
                    tenant.fence(id, true);
                    var t =
                            db.queryForMap("select state,owner_subject from tenants where id=? for update", id);
                    String state = (String) t.get("state");
                    if (state.equals("ACTIVE")) return Map.of("state", "ACTIVE");
                    if (!Set.of("PENDING", "FAILED", "PROVISIONING").contains(state))
                        throw ApiError.conflict("Invalid lifecycle transition");
                    db.update("update tenants set state='PROVISIONING' where id=?", id);
                    tenant.scope(id);
                    db.update(
                            "insert into memberships(tenant_id,subject,role) values(?,?,'TENANT_OWNER') on"
                                    + " conflict do nothing",
                            id,
                            t.get("owner_subject"));
                    db.update("insert into usage(tenant_id) values(?) on conflict do nothing", id);
                    db.update(
                            "insert into outbox(tenant_id,id,aggregate_id,version,type,correlation)"
                                    + " values(?,?,?,1,'TenantActivated',?)",
                            id,
                            UUID.randomUUID(),
                            id,
                            UUID.randomUUID());
                    db.update("update tenants set state='ACTIVE' where id=?", id);
                    log(j, id, "TENANT_ACTIVATED");
                    return Map.of("state", "ACTIVE");
                });
    }

    @PostMapping("/tenants/{id}/suspend")
    Object suspend(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return transition(j, id, "ACTIVE", "SUSPENDED");
    }

    @PostMapping("/tenants/{id}/activate")
    Object activate(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return transition(j, id, "SUSPENDED", "ACTIVE");
    }

    Object transition(Jwt j, UUID id, String from, String to) {
        platform(j);
        return tx.execute(
                s -> {
                    tenant.fence(id, true);
                    int n = db.update("update tenants set state=? where id=? and state=?", to, id, from);
                    if (n == 0) throw ApiError.conflict("Invalid lifecycle transition");
                    afterCommitInvalidation(id);
                    log(j, id, "TENANT_" + to);
                    return Map.of("state", to);
                });
    }

    @PostMapping("/tenants/{id}/deletion")
    Object delete(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    if (!c.role().equals("TENANT_OWNER")) throw ApiError.forbidden();
                    db.update(
                            "update tenants set state='DEPROVISIONING',delete_after=now()+interval '30 days'"
                                    + " where id=?",
                            id);
                    db.update("update jobs set state='CANCELLED',result=null where tenant_id=?", id);
                    afterCommitInvalidation(id);
                    tenant.audit(c, "DELETION_REQUESTED", id);
                    return Map.of("state", "DEPROVISIONING");
                });
    }

    @PostMapping("/tenants/{id}/invitations")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object invite(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Invitation b) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    UUID inv = UUID.randomUUID();
                    db.update(
                            "insert into invitations(tenant_id,id,subject,role) values(?,?,?,?) on"
                                    + " conflict(tenant_id,subject) do update set"
                                    + " role=excluded.role,expires_at=now()+interval '7 days',accepted=false",
                            id,
                            inv,
                            b.subject(),
                            b.role());
                    tenant.audit(c, "MEMBER_INVITED", b.subject());
                    return db.queryForMap(
                            "select id,subject,role from invitations where tenant_id=? and subject=?",
                            id,
                            b.subject());
                });
    }

    @PostMapping("/invitations/{inv}/accept")
    Object accept(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID id,
            @PathVariable UUID inv) {
        return tx.execute(
                s -> {
                    String claim = j.getClaimAsString("tenant_id");
                    if (claim != null && !claim.equals(id.toString())) throw ApiError.forbidden();
                    tenant.scopeAndFence(id, true);
                    db.queryForMap("select id from tenants where id=? and state='ACTIVE' for update", id);
                    var b =
                            db.queryForList(
                                    "select subject,role from invitations where id=? and subject=? and"
                                            + " expires_at>now() and not accepted for update",
                                    inv,
                                    j.getSubject());
                    if (b.isEmpty()) throw ApiError.missing();
                    int seats =
                            db.queryForObject(
                                    "select p.seats from tenants t join plans p on p.name=t.plan where t.id=?",
                                    Integer.class,
                                    id);
                    int used =
                            db.queryForObject(
                                    "select count(*) from memberships where tenant_id=? and active",
                                    Integer.class,
                                    id);
                    boolean existing =
                            Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "select exists(select 1 from memberships where tenant_id=? and subject=? and"
                                                    + " active)",
                                            Boolean.class,
                                            id,
                                            j.getSubject()));
                    if (used >= seats && !existing) throw ApiError.conflict("Seat quota exceeded");
                    db.update(
                            "insert into memberships(tenant_id,subject,role) values(?,?,?) on"
                                    + " conflict(tenant_id,subject) do update set active=true,role=case when"
                                    + " memberships.role='TENANT_OWNER' then memberships.role else excluded.role end",
                            id,
                            j.getSubject(),
                            b.getFirst().get("role"));
                    db.update("update invitations set accepted=true where id=?", inv);
                    log(j, id, "INVITATION_ACCEPTED");
                    return Map.of("accepted", true);
                });
    }

    @GetMapping("/tenants/{id}/members")
    Object members(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c ->
                        db.queryForList(
                                "select subject,role,active from memberships where tenant_id=? order by subject"
                                        + " limit 1000",
                                id));
    }

    @DeleteMapping("/tenants/{id}/members/{subject}")
    Object remove(
            @AuthenticationPrincipal Jwt j, @PathVariable UUID id, @PathVariable String subject) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    int n =
                            db.update(
                                    "update memberships set active=false where tenant_id=? and subject=? and"
                                            + " role<>'TENANT_OWNER'",
                                    id,
                                    subject);
                    if (n == 0) throw ApiError.missing();
                    tenant.audit(c, "MEMBER_REMOVED", subject);
                    return Map.of("removed", true);
                });
    }

    @PatchMapping("/tenants/{id}/members/{subject}")
    Object role(
            @AuthenticationPrincipal Jwt j,
            @PathVariable UUID id,
            @PathVariable String subject,
            @Valid @RequestBody Invitation b) {
        return tenant.user(
                id,
                j,
                true,
                c -> {
                    if (!c.role().equals("TENANT_OWNER")) throw ApiError.forbidden();
                    if (db.update(
                            "update memberships set role=? where tenant_id=? and subject=? and"
                                    + " role<>'TENANT_OWNER'",
                            b.role(),
                            id,
                            subject)
                            == 0) throw ApiError.missing();
                    tenant.audit(c, "ROLE_CHANGED", subject);
                    return Map.of("updated", true);
                });
    }

    @GetMapping("/tenants/{id}/subscription")
    Object subscription(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c ->
                        db.queryForMap(
                                "select plan,subscription,trial_ends_at,cancel_at_period_end from tenants where"
                                        + " id=?",
                                id));
    }

    @PostMapping("/tenants/{id}/subscription")
    Object plan(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Plan b) {
        if (!sandbox) throw ApiError.forbidden();
        return tenant.control(
                id,
                j,
                c -> {
                    if (!c.role().equals("TENANT_OWNER")) throw ApiError.forbidden();
                    db.update("update tenants set plan=?,subscription='ACTIVE' where id=?", b.plan(), id);
                    db.update(
                            "update tenants set cancel_at_period_end=false,cancel_effective_at=null where id=?",
                            id);
                    db.update(
                            "insert into billing_history values(?,?, 'ACTIVE',?,?,now())",
                            id,
                            UUID.randomUUID(),
                            b.plan(),
                            c.actor());
                    tenant.audit(c, "SANDBOX_PLAN_CHANGED", b.plan());
                    return Map.of("plan", b.plan(), "sandbox", true);
                });
    }

    @GetMapping("/tenants/{id}/entitlements")
    Object entitlements(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c ->
                        db.queryForMap(
                                "select p.*,t.config from tenants t join plans p on p.name=t.plan where t.id=?",
                                id));
    }

    @GetMapping("/tenants/{id}/usage")
    Object usage(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c ->
                        db.queryForMap(
                                "select u.*,u.api as api_reserved,greatest(u.api-u.api_confirmed-u.api_assumed,0)"
                                        + " as api_inflight_or_indeterminate,(select count(*) from projects) as"
                                        + " projects,(select coalesce(sum(bytes),0) from usage_storage) as"
                                        + " storage_bytes,(select count(*) from memberships where tenant_id=? and"
                                        + " active) as seats from usage u where period=date_trunc('month',now())::date",
                                id));
    }

    @GetMapping("/tenants/{id}/audit")
    Object audit(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return tenant.user(
                id,
                j,
                false,
                c -> {
                    c.requireAdmin();
                    return db.queryForList("select * from audit order by at desc limit 100");
                });
    }

    void afterCommitInvalidation(UUID id) {
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            public void afterCommit() {
                                cache.invalidate(id);
                            }
                        });
    }

    record Registration(
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,62}") @NotNull String slug,
            @NotBlank @Size(max = 160) String name) {
    }

    record Invitation(
            @NotBlank @Size(max = 200) String subject,
            @Pattern(regexp = "TENANT_ADMIN|TENANT_MEMBER|TENANT_VIEWER") @NotNull String role) {
    }

    record Plan(@Pattern(regexp = "FREE|STARTER|BUSINESS|ENTERPRISE") @NotNull String plan) {
    }

    record Config(@NotBlank @Size(max = 160) String name, boolean exportEnabled) {
    }
}
