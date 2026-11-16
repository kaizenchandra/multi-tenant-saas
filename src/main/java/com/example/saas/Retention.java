package com.example.saas;

import java.util.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "saas.workers", havingValue = "true")
class Retention {
    final JdbcTemplate db;
    final TenantTransactions tenant;
    final TransactionTemplate tx;

    Retention(JdbcTemplate db, TenantTransactions tenant, PlatformTransactionManager m) {
        this.db = db;
        this.tenant = tenant;
        tx = new TransactionTemplate(m);
        tx.setTimeout(10);
    }

    @Scheduled(fixedDelay = 60000)
    void purge() {
        for (UUID id :
                db.queryForList(
                        "select id from tenants where state='DEPROVISIONING' and not legal_hold and"
                                + " delete_after<now() order by id limit 5",
                        UUID.class)) {
            tx.executeWithoutResult(
                    s -> {
                        tenant.fence(id, true);
                        var rows =
                                db.queryForList(
                                        "select id from tenants where id=? and state='DEPROVISIONING' and not"
                                                + " legal_hold and delete_after<now() for update skip locked",
                                        id);
                        if (rows.isEmpty()) return;
                        tenant.scope(id);
                        for (String table :
                                List.of(
                                        "export_chunks",
                                        "api_budgets",
                                        "event_projection",
                                        "feature_flags",
                                        "idempotency",
                                        "inbox",
                                        "outbox",
                                        "jobs",
                                        "task_assignments",
                                        "project_members",
                                        "tasks",
                                        "projects",
                                        "invitations",
                                        "usage",
                                        "usage_storage")) {
                            db.update(
                                    "delete from "
                                            + table
                                            + " where ctid in (select ctid from "
                                            + table
                                            + " where tenant_id=? limit 500)",
                                    id);
                            if (db.queryForObject(
                                    "select exists(select 1 from " + table + " where tenant_id=?)",
                                    Boolean.class,
                                    id)) return;
                        }
                        db.update("update memberships set active=false where tenant_id=?", id);
                        db.update(
                                "update tenants set state='DELETED',config='{}',name='Deleted tenant' where id=?",
                                id);
                        db.update(
                                "insert into platform_audit(tenant_id,actor,action)"
                                        + " values(?,'system:retention','TENANT_DELETED')",
                                id);
                    });
        }
    }
}
