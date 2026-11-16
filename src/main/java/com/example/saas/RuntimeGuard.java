package com.example.saas;

import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
class RuntimeGuard implements org.springframework.beans.factory.InitializingBean {
    final JdbcTemplate db;

    RuntimeGuard(JdbcTemplate db) {
        this.db = db;
    }

    public void afterPropertiesSet() {
        run(null);
    }

    public void run(ApplicationArguments args) {
        var role =
                db.queryForMap("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
        if (Boolean.TRUE.equals(role.get("rolsuper")) || Boolean.TRUE.equals(role.get("rolbypassrls")))
            throw new IllegalStateException("Unsafe database runtime role");
        Integer count =
                db.queryForObject(
                        "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where"
                                + " n.nspname='public' and c.relname in"
                                + " ('projects','tasks','project_members','task_assignments','audit','usage','jobs','inbox','outbox','invitations','feature_flags','idempotency','billing_history','event_projection','usage_storage','api_budgets','export_chunks')"
                                + " and c.relrowsecurity and c.relforcerowsecurity",
                        Integer.class);
        if (count != 17) throw new IllegalStateException("RLS configuration incomplete");
        if (db.queryForObject(
                "select count(*) from pg_trigger where tgname in"
                        + " ('tenants_control_fence','memberships_control_fence') and tgenabled='O' and"
                        + " tgfoid='guard_tenant_control_change()'::regprocedure",
                Integer.class)
                != 2) throw new IllegalStateException("Missing control-plane fencing triggers");
        if (db.queryForObject(
                "select count(*) from pg_trigger where tgname in"
                        + " ('projects_query_epoch','tasks_query_epoch') and tgenabled='O' and"
                        + " tgfoid='bump_tenant_data_epoch()'::regprocedure",
                Integer.class)
                != 2) throw new IllegalStateException("Missing query-cache invalidation triggers");
        if (db.queryForObject(
                "select count(*) from pg_trigger where tgrelid in"
                        + " ('projects'::regclass,'tasks'::regclass,'jobs'::regclass,'export_chunks'::regclass)"
                        + " and tgfoid='account_statement_storage()'::regprocedure and tgenabled='O'",
                Integer.class)
                != 12) throw new IllegalStateException("Missing storage accounting triggers");
        String expected =
                "(tenant_id = (NULLIF(current_setting('app.current_tenant'::text, true), ''::text))::uuid)";
        for (String table :
                java.util.List.of(
                        "projects",
                        "tasks",
                        "project_members",
                        "task_assignments",
                        "audit",
                        "usage",
                        "jobs",
                        "inbox",
                        "outbox",
                        "invitations",
                        "feature_flags",
                        "idempotency",
                        "billing_history",
                        "event_projection",
                        "usage_storage",
                        "api_budgets",
                        "export_chunks")) {
            var policies =
                    db.queryForList(
                            "select polcmd::text as command,polpermissive,pg_get_expr(polqual,polrelid) as"
                                    + " using_expr,pg_get_expr(polwithcheck,polrelid) as check_expr from pg_policy"
                                    + " where polrelid=('public.'||?)::regclass",
                            table);
            if (policies.size() != 1
                    || !"*".equals(policies.getFirst().get("command"))
                    || !expected.equals(policies.getFirst().get("using_expr"))
                    || !expected.equals(policies.getFirst().get("check_expr")))
                throw new IllegalStateException("Unexpected tenant RLS policy: " + table);
            if (Boolean.TRUE.equals(
                    db.queryForObject(
                            "select pg_has_role(current_user,relowner,'MEMBER') from pg_class where"
                                    + " oid=('public.'||?)::regclass",
                            Boolean.class,
                            table))) throw new IllegalStateException("Runtime inherits table ownership");
        }
    }
}
