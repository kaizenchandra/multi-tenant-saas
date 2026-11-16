package com.example.saas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ProjectApi {
    final TenantTransactions tenant;
    final JdbcTemplate db;
    final TenantCache cache;
    final Mutations mutations;
    final OperationsApi operations;
    final QueryCache queries;

    ProjectApi(
            TenantTransactions tenant,
            JdbcTemplate db,
            TenantCache cache,
            Mutations mutations,
            OperationsApi operations,
            QueryCache queries) {
        this.tenant = tenant;
        this.db = db;
        this.cache = cache;
        this.mutations = mutations;
        this.operations = operations;
        this.queries = queries;
    }

    void event(TenantContext c, UUID id, String type) {
        tenant.audit(c, type, id);
        db.update(
                "insert into outbox(tenant_id,id,aggregate_id,version,type,correlation)"
                        + " values(?,?,?,coalesce((select version from projects where id=?),0),?,?)",
                c.tenantId(),
                UUID.randomUUID(),
                id,
                id,
                type,
                c.correlation());
    }

    Map<String, Object> project(UUID id) {
        var rows = db.queryForList("select id,name,status,version from projects where id=?", id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    @PostMapping("/projects")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object createRequest(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @Valid @RequestBody ProjectInput b,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return tenant.user(
                t, j, true, c -> mutations.once(c, key, "POST /projects", b, () -> createProject(c, b)));
    }

    Object create(Jwt j, UUID t, ProjectInput b) {
        return tenant.user(t, j, true, c -> createProject(c, b));
    }

    Object createProject(TenantContext c, ProjectInput b) {
        int max =
                db.queryForObject(
                        "select p.projects from tenants t join plans p on t.plan=p.name where t.id=?",
                        Integer.class,
                        c.tenantId());
        if (db.queryForObject("select count(*) from projects", Integer.class) >= max)
            throw ApiError.conflict("Project quota exceeded");
        UUID id = UUID.randomUUID();
        db.update(
                "insert into projects(tenant_id,id,name,status) values(?,?,?,?)",
                c.tenantId(),
                id,
                b.name(),
                b.status());
        event(c, id, "ProjectCreated");
        return project(id);
    }

    @GetMapping("/projects")
    Object list(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @RequestParam(defaultValue = "") @Size(max = 160) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100 || q.length() > 160)
            throw new IllegalArgumentException();
        return tenant.user(
                t,
                j,
                false,
                c ->
                        queries.list(
                                c,
                                q,
                                page,
                                size,
                                () ->
                                        Map.of(
                                                "items",
                                                db
                                                        .queryForList(
                                                                "select p.id,p.name,p.status,p.version,count(t.id) as tasks from"
                                                                        + " projects p left join tasks t on t.tenant_id=p.tenant_id and"
                                                                        + " t.project_id=p.id where strpos(lower(p.name),lower(?))>0"
                                                                        + " group by p.tenant_id,p.id order by p.id limit ? offset ?",
                                                                q,
                                                                size,
                                                                page * size)
                                                        .stream()
                                                        .map(Map::copyOf)
                                                        .toList(),
                                                "page",
                                                page,
                                                "size",
                                                size)));
    }

    @GetMapping("/projects/{id}")
    Object get(
            @AuthenticationPrincipal Jwt j, @RequestHeader("X-Tenant-ID") UUID t, @PathVariable UUID id) {
        return tenant.user(
                t,
                j,
                false,
                c -> {
                    var row = project(id);
                    return cache.readThrough(c, id, row);
                });
    }

    @PatchMapping("/projects/{id}")
    Object update(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @PathVariable UUID id,
            @Valid @RequestBody ProjectInput b,
            @RequestHeader(value = "If-Match", required = false) String expectedVersion) {
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    var existing = project(id);
                    if (expectedVersion != null
                            && ((Number) existing.get("version")).longValue()
                            != Long.parseLong(expectedVersion.replace("\"", "")))
                        throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.PRECONDITION_FAILED, "Project version changed");
                    if (db.update(
                            "update projects set name=?,status=?,version=version+1 where id=?",
                            b.name(),
                            b.status(),
                            id)
                            == 0) throw ApiError.missing();
                    event(c, id, "ProjectUpdated");
                    return project(id);
                });
    }

    @DeleteMapping("/projects/{id}")
    Object delete(
            @AuthenticationPrincipal Jwt j, @RequestHeader("X-Tenant-ID") UUID t, @PathVariable UUID id) {
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    if (db.update("delete from projects where id=?", id) == 0) throw ApiError.missing();
                    event(c, id, "ProjectDeleted");
                    return Map.of("deleted", true);
                });
    }

    @PostMapping("/projects/{id}/tasks")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object task(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @PathVariable UUID id,
            @Valid @RequestBody TaskInput b,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return tenant.user(
                t,
                j,
                true,
                c ->
                        mutations.once(
                                c,
                                key,
                                "POST /projects/" + id + "/tasks",
                                b,
                                () -> {
                                    project(id);
                                    UUID task = UUID.randomUUID();
                                    db.update(
                                            "insert into tasks(tenant_id,id,project_id,title,status) values(?,?,?,?,?)",
                                            t,
                                            task,
                                            id,
                                            b.title(),
                                            b.status());
                                    db.update("update projects set version=version+1 where id=?", id);
                                    event(c, id, "TaskCreated");
                                    return Map.of("id", task, "title", b.title(), "status", b.status());
                                }));
    }

    @GetMapping("/projects/{id}/tasks")
    Object tasks(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 100000) throw new IllegalArgumentException();
        return tenant.user(
                t,
                j,
                false,
                c -> {
                    project(id);
                    return db.queryForList(
                            "select * from tasks where project_id=? order by id limit 100 offset ?",
                            id,
                            page * 100);
                });
    }

    @PatchMapping("/tasks/{id}")
    Object taskUpdate(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @PathVariable UUID id,
            @Valid @RequestBody TaskInput b) {
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    if (db.update("update tasks set title=?,status=? where id=?", b.title(), b.status(), id)
                            == 0) throw ApiError.missing();
                    tenant.audit(c, "TASK_UPDATED", id);
                    return Map.of("id", id);
                });
    }

    @DeleteMapping("/tasks/{id}")
    Object taskDelete(
            @AuthenticationPrincipal Jwt j, @RequestHeader("X-Tenant-ID") UUID t, @PathVariable UUID id) {
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    if (db.update("delete from tasks where id=?", id) == 0) throw ApiError.missing();
                    tenant.audit(c, "TASK_DELETED", id);
                    return Map.of("deleted", true);
                });
    }

    @PostMapping("/projects/{id}/members")
    Object member(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @PathVariable UUID id,
            @Valid @RequestBody Assignment b) {
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    project(id);
                    verifyMember(t, b.subject());
                    db.update(
                            "insert into project_members values(?,?,?) on conflict do nothing",
                            t,
                            id,
                            b.subject());
                    tenant.audit(c, "PROJECT_MEMBER_ADDED", id);
                    return Map.of("added", true);
                });
    }

    @PostMapping("/tasks/{id}/assignments")
    Object assign(
            @AuthenticationPrincipal Jwt j,
            @RequestHeader("X-Tenant-ID") UUID t,
            @PathVariable UUID id,
            @Valid @RequestBody Assignment b) {
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    verifyMember(t, b.subject());
                    if (db.queryForObject("select count(*) from tasks where id=?", Integer.class, id) == 0)
                        throw ApiError.missing();
                    db.update(
                            "insert into task_assignments values(?,?,?) on conflict do nothing",
                            t,
                            id,
                            b.subject());
                    tenant.audit(c, "TASK_ASSIGNED", id);
                    return Map.of("assigned", true);
                });
    }

    void verifyMember(UUID t, String subject) {
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        "select exists(select 1 from memberships where tenant_id=? and subject=? and active)",
                        Boolean.class,
                        t,
                        subject))) throw ApiError.missing();
    }

    @PostMapping("/projects/{id}/complete-tasks")
    Object bulk(
            @AuthenticationPrincipal Jwt j, @RequestHeader("X-Tenant-ID") UUID t, @PathVariable UUID id) {
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    project(id);
                    int n = db.update("update tasks set status='DONE' where project_id=?", id);
                    tenant.audit(c, "TASKS_COMPLETED", id);
                    return Map.of("updated", n);
                });
    }

    @GetMapping("/dashboard")
    Object dashboard(@AuthenticationPrincipal Jwt j, @RequestHeader("X-Tenant-ID") UUID t) {
        return tenant.user(
                t,
                j,
                false,
                c ->
                        Map.of(
                                "projects",
                                db.queryForList("select status,count(*) from projects group by status"),
                                "tasks",
                                db.queryForList("select status,count(*) from tasks group by status")));
    }

    @PostMapping({"/tenants/{t}/export", "/admin/tenants/{t}/export"})
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    Object export(
            @AuthenticationPrincipal Jwt j,
            @PathVariable UUID t,
            jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().contains("/admin/")) {
            ControlPlane.platform(j);
            Object result =
                    tenant.system(
                            t,
                            c ->
                                    queue(
                                            new TenantContext(t, j.getSubject(), "TENANT_OWNER", UUID.randomUUID()),
                                            true));
            if (result == null) throw ApiError.forbidden();
            return result;
        }
        return tenant.user(
                t,
                j,
                true,
                c -> {
                    c.requireAdmin();
                    return queue(c);
                });
    }

    Object queue(TenantContext c) {
        return queue(c, false);
    }

    Object queue(TenantContext c, boolean support) {
        var p =
                db.queryForMap(
                        "select p.export_enabled,p.jobs,coalesce((t.config->>'exportEnabled')::boolean,true) as"
                                + " flag from tenants t join plans p on p.name=t.plan where t.id=?",
                        c.tenantId());
        if (!operations.enabled(c, "export")) throw ApiError.forbidden();
        db.update("insert into usage(tenant_id) values(?) on conflict do nothing", c.tenantId());
        if (!Boolean.TRUE.equals(p.get("export_enabled")) || !Boolean.TRUE.equals(p.get("flag")))
            throw ApiError.forbidden();
        if (db.update(
                "update usage set jobs=jobs+1 where tenant_id=? and"
                        + " period=date_trunc('month',now())::date and jobs<?",
                c.tenantId(),
                p.get("jobs"))
                == 0) throw ApiError.conflict("Job quota exceeded");
        UUID id = UUID.randomUUID();
        db.update(
                "insert into jobs(tenant_id,id,kind,requested_by,support_export) values(?,?,'EXPORT',?,?)",
                c.tenantId(),
                id,
                c.actor(),
                support);
        tenant.audit(c, "EXPORT_REQUESTED", id);
        return support
                ? Map.of(
                "id",
                id,
                "status",
                "/api/v1/admin/tenants/" + c.tenantId() + "/exports/" + id,
                "download",
                "/api/v1/admin/tenants/" + c.tenantId() + "/exports/" + id + "/content")
                : Map.of("id", id);
    }

    @GetMapping("/jobs/{id}")
    Object job(
            @AuthenticationPrincipal Jwt j, @RequestHeader("X-Tenant-ID") UUID t, @PathVariable UUID id) {
        return tenant.user(
                t,
                j,
                false,
                c -> {
                    c.requireAdmin();
                    var rows = db.queryForList("select * from jobs where id=?", id);
                    if (rows.isEmpty()) throw ApiError.missing();
                    var result = new HashMap<String, Object>(rows.getFirst());
                    if (result.get("result") != null) {
                        try {
                            result.put("result", mutations.json.readTree(result.get("result").toString()));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return result;
                });
    }

    record ProjectInput(
            @NotBlank @Size(max = 160) String name,
            @Pattern(regexp = "OPEN|ACTIVE|CLOSED") @NotNull String status) {
    }

    record TaskInput(
            @NotBlank @Size(max = 200) String title,
            @Pattern(regexp = "TODO|DOING|DONE") @NotNull String status) {
    }

    record Assignment(@NotBlank String subject) {
    }
}
