package com.example.saas;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IsolationTest {
    @Container
    static PostgreSQLContainer<?> pg =
            new PostgreSQLContainer<>("postgres:17.6")
                    .withDatabaseName("saas")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withInitScript("init.sql");
    @Autowired
    JdbcTemplate db;
    @Autowired
    TenantTransactions tenant;
    @Autowired
    ProjectApi projects;
    @Autowired
    MockMvc mvc;
    @Autowired
    StreamingExports streaming;
    UUID a, b, id;
    String user;
    Jwt identity;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", pg::getJdbcUrl);
        p.add("spring.datasource.password", () -> "local-runtime-only");
        p.add("saas.workers", () -> "false");
        p.add("saas.billing-secret", () -> "test-billing-secret-32-bytes-minimum");
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
    }

    static Jwt identity(String sub) {
        return Jwt.withTokenValue("mock").header("alg", "RS256").subject(sub).build();
    }

    @BeforeEach
    void seed() {
        a = UUID.randomUUID();
        b = UUID.randomUUID();
        id = UUID.randomUUID();
        user = "u-" + a;
        identity = identity(user);
        for (UUID t : List.of(a, b)) {
            db.update(
                    "insert into tenants(id,slug,name,owner_subject,state) values(?,?,?,?,'ACTIVE')",
                    t,
                    t.toString(),
                    "Tenant",
                    user);
            db.update(
                    "insert into memberships(tenant_id,subject,role) values(?,?,'TENANT_OWNER')", t, user);
            tenant.system(
                    t,
                    c -> {
                        db.update(
                                "insert into projects(tenant_id,id,name) values(?,?,?)", t, id, "Project " + t);
                        return null;
                    });
        }
    }

    @Test
    void omittedPredicatesNativeBulkAndJoinsStayIsolated() {
        tenant.user(
                a,
                identity,
                true,
                c -> {
                    assertThat(db.queryForList("select * from projects")).hasSize(1);
                    assertThat(db.queryForObject("select tenant_id from projects where id=?", UUID.class, id))
                            .isEqualTo(a);
                    assertThat(db.update("update projects set status='ACTIVE'")).isEqualTo(1);
                    db.update("insert into tasks values(?,?,?,'task','TODO')", a, UUID.randomUUID(), id);
                    assertThat(
                            db.queryForList(
                                    "select * from projects p join tasks t on p.id=t.project_id and"
                                            + " p.tenant_id=t.tenant_id"))
                            .hasSize(1);
                    return null;
                });
        tenant.user(
                b,
                identity,
                false,
                c -> {
                    assertThat(db.queryForObject("select status from projects", String.class))
                            .isEqualTo("OPEN");
                    assertThat(db.queryForList("select * from tasks")).isEmpty();
                    return null;
                });
    }

    @Test
    void missingSettingAndConnectionReuseFailClosed() {
        for (int i = 0; i < 12; i++) {
            UUID t = i % 2 == 0 ? a : b;
            tenant.user(
                    t,
                    identity,
                    false,
                    c -> {
                        assertThat(db.queryForObject("select tenant_id from projects", UUID.class))
                                .isEqualTo(t);
                        return null;
                    });
            assertThat(db.queryForList("select * from projects")).isEmpty();
        }
        assertThatThrownBy(
                () ->
                        db.update("insert into projects values(?,?,'bad','OPEN',0)", a, UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void crossTenantWritesAndCompositeForeignKeysDenied() {
        assertThatThrownBy(
                () ->
                        tenant.user(
                                a,
                                identity,
                                true,
                                c -> {
                                    db.update(
                                            "insert into projects values(?,?,'wrong','OPEN',0)",
                                            b,
                                            UUID.randomUUID());
                                    return null;
                                }))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID onlyB = UUID.randomUUID();
        tenant.system(
                b,
                c -> {
                    db.update("insert into projects values(?,?,'b-only','OPEN',0)", b, onlyB);
                    return null;
                });
        assertThatThrownBy(
                () ->
                        tenant.user(
                                a,
                                identity,
                                true,
                                c -> {
                                    db.update(
                                            "insert into tasks values(?,?,?,'wrong','TODO')",
                                            a,
                                            UUID.randomUUID(),
                                            onlyB);
                                    return null;
                                }))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        tenant.user(
                a,
                identity,
                true,
                c -> {
                    assertThat(db.update("delete from projects where tenant_id=?", b)).isZero();
                    assertThat(db.update("update projects set status='CLOSED' where tenant_id=?", b))
                            .isZero();
                    return null;
                });
    }

    @Test
    void requiresNewRestoresOuterConnectionContext() {
        tenant.user(
                a,
                identity,
                false,
                c -> {
                    assertThat(db.queryForObject("select tenant_id from projects", UUID.class)).isEqualTo(a);
                    tenant.user(
                            b,
                            identity,
                            false,
                            d -> {
                                assertThat(db.queryForObject("select tenant_id from projects", UUID.class))
                                        .isEqualTo(b);
                                return null;
                            });
                    assertThat(db.queryForObject("select tenant_id from projects", UUID.class)).isEqualTo(a);
                    return null;
                });
    }

    @Test
    void runtimeCannotBypassAndAuditIsAppendOnly() {
        var role =
                db.queryForMap("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
        assertThat(role.values()).containsOnly(false);
        assertThatThrownBy(() -> db.execute("alter table projects disable row level security"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> db.update("delete from audit"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void forgedHeaderIdorAndStaleMembership() throws Exception {
        mvc.perform(
                        get("/api/v1/projects")
                                .header("X-Tenant-ID", a)
                                .with(jwt().jwt(j -> j.subject("intruder"))))
                .andExpect(status().isForbidden());
        UUID foreign = UUID.randomUUID();
        tenant.system(
                b,
                c -> {
                    db.update("insert into projects values(?,?,'foreign','OPEN',0)", b, foreign);
                    return null;
                });
        mvc.perform(
                        get("/api/v1/projects/" + foreign)
                                .header("X-Tenant-ID", a)
                                .with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isNotFound());
        db.update("update memberships set active=false where tenant_id=? and subject=?", a, user);
        mvc.perform(
                        get("/api/v1/projects").header("X-Tenant-ID", a).with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isForbidden());
    }

    @Test
    void multiMembershipSwitchAndClaimConflict() throws Exception {
        for (UUID t : List.of(a, b))
            mvc.perform(
                            get("/api/v1/projects")
                                    .header("X-Tenant-ID", t)
                                    .with(jwt().jwt(j -> j.subject(user))))
                    .andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/projects")
                                .header("X-Tenant-ID", b)
                                .with(jwt().jwt(j -> j.subject(user).claim("tenant_id", a.toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspensionAndAdminPrivilege() throws Exception {
        db.update("update tenants set state='SUSPENDED' where id=?", a);
        mvc.perform(
                        get("/api/v1/projects").header("X-Tenant-ID", a).with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/tenants").with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotWrite() throws Exception {
        db.update("update memberships set role='TENANT_VIEWER' where tenant_id=?", a);
        mvc.perform(
                        post("/api/v1/projects")
                                .header("X-Tenant-ID", a)
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"attack\",\"status\":\"OPEN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrencyHardQuota() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                int n = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        for (int attempt = 0; ; attempt++) {
                                            try {
                                                projects.create(identity, a, new ProjectApi.ProjectInput("p-" + n, "OPEN"));
                                                break;
                                            } catch (org.springframework.web.server.ResponseStatusException busy) {
                                                if (busy.getStatusCode().value() != 429 || attempt >= 200) throw busy;
                                                Thread.sleep(5);
                                            }
                                        }
                                        return true;
                                    } catch (org.springframework.web.server.ResponseStatusException e) {
                                        return false;
                                    }
                                }));
            }
            int succeeded = 0;
            for (var f : futures) if (f.get(20, TimeUnit.SECONDS)) succeeded++;
            assertThat(succeeded).isEqualTo(4);
        }
        tenant.user(
                a,
                identity,
                false,
                c -> {
                    assertThat(db.queryForObject("select count(*) from projects", Integer.class))
                            .isEqualTo(5);
                    return null;
                });
    }

    @Test
    void reusedThreadsDoNotCarryContext() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            for (UUID t : List.of(a, b, a, b)) {
                assertThat(
                        pool.submit(
                                        () ->
                                                tenant.user(
                                                        t,
                                                        identity,
                                                        false,
                                                        c ->
                                                                db.queryForObject(
                                                                        "select tenant_id from projects", UUID.class)))
                                .get())
                        .isEqualTo(t);
                assertThat(pool.submit(() -> db.queryForList("select * from projects").size()).get())
                        .isZero();
            }
        }
    }

    @Test
    void cacheKeysCannotCollide() {
        assertThat(TenantCache.key(a, id, 0)).isNotEqualTo(TenantCache.key(b, id, 0));
        assertThatNullPointerException().isThrownBy(() -> TenantCache.key(null, id, 0));
    }

    @Test
    void duplicateProvisioningAndInvitationReplay() throws Exception {
        UUID t = UUID.randomUUID();
        db.update(
                "insert into tenants values(?,?,? ,?,'PENDING','FREE','TRIALING',false,'{}',now(),null)",
                t,
                t.toString(),
                "pending",
                user);
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/v1/admin/tenants/" + t + "/provision")
                                    .with(
                                            jwt()
                                                    .jwt(
                                                            j ->
                                                                    j.subject("admin")
                                                                            .claim(
                                                                                    "realm_access",
                                                                                    Map.of("roles", List.of("PLATFORM_ADMIN"))))))
                    .andExpect(status().isOk());
        assertThat(
                db.queryForObject(
                        "select count(*) from memberships where tenant_id=?", Integer.class, t))
                .isEqualTo(1);
        UUID inv = UUID.randomUUID();
        tenant.system(
                t,
                c -> {
                    db.update(
                            "insert into invitations(tenant_id,id,subject,role) values(?,?,?,'TENANT_MEMBER')",
                            t,
                            inv,
                            "invitee");
                    return null;
                });
        mvc.perform(
                        post("/api/v1/invitations/" + inv + "/accept")
                                .header("X-Tenant-ID", t)
                                .with(jwt().jwt(j -> j.subject("invitee"))))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/invitations/" + inv + "/accept")
                                .header("X-Tenant-ID", t)
                                .with(jwt().jwt(j -> j.subject("invitee"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void readOnlyTransactionIsScoped() {
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource()));
        var read = new org.springframework.transaction.support.TransactionTemplate(manager);
        read.setReadOnly(true);
        read.executeWithoutResult(
                st -> {
                    tenant.scope(a);
                    assertThat(db.queryForObject("select tenant_id from projects", UUID.class)).isEqualTo(a);
                });
        assertThat(db.queryForList("select * from projects")).isEmpty();
    }

    @Test
    void consumerRejectsWrongTenantAndDeduplicates() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var consumer = new EventConsumer(tenant, db, json);
        UUID event = UUID.randomUUID(), correlation = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into outbox(tenant_id,id,aggregate_id,version,type,correlation)"
                                    + " values(?,?,?,1,'TestEvent',?)",
                            a,
                            event,
                            id,
                            correlation);
                    return null;
                });
        Map<String, Object> envelope =
                new HashMap<>(
                        Map.of(
                                "tenant_id",
                                a,
                                "id",
                                event,
                                "aggregate_id",
                                id,
                                "version",
                                1,
                                "type",
                                "TestEvent",
                                "correlation",
                                correlation));
        String body = json.writeValueAsString(envelope);
        consumer.accept(body);
        consumer.accept(body);
        tenant.user(
                a,
                identity,
                false,
                c -> {
                    assertThat(db.queryForObject("select count(*) from inbox", Integer.class)).isEqualTo(1);
                    return null;
                });
        envelope.put("tenant_id", b);
        String forged = json.writeValueAsString(envelope);
        assertThatThrownBy(() -> consumer.accept(forged))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void backgroundExportCannotMixTenants() {
        UUID job = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into jobs(tenant_id,id,kind,requested_by) values(?,?,'EXPORT',?)",
                            a,
                            job,
                            user);
                    return null;
                });
        durable(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class)).runOne(a);
        tenant.system(
                a,
                c -> {
                    String result =
                            db.queryForObject("select result::text from jobs where id=?", String.class, job);
                    assertThat(result).contains("compressed_bytes").doesNotContain(b.toString());
                    return null;
                });
        try {
            byte[] zipped =
                    mvc.perform(
                                    get("/api/v1/jobs/" + job + "/content")
                                            .with(jwt().jwt(j -> j.subject(user)))
                                            .header("X-Tenant-ID", a))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsByteArray();
            try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(zipped))) {
                assertThat(new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains(a.toString())
                        .doesNotContain(b.toString());
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        tenant.system(
                a,
                c -> {
                    return null;
                });
        tenant.system(
                b,
                c -> {
                    assertThat(db.queryForList("select * from jobs where id=?", job)).isEmpty();
                    return null;
                });
        assertThatNullPointerException().isThrownBy(() -> tenant.system(null, c -> null));
    }

    @Test
    void kafkaOutageRetainsOutbox() {
        var kafka = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(
                        kafka.send(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into outbox(tenant_id,id,aggregate_id,version,type,correlation)"
                                    + " values(?,?,?,1,'Test',?)",
                            a,
                            UUID.randomUUID(),
                            id,
                            UUID.randomUUID());
                    return null;
                });
        durable(kafka).dispatchOne(a);
        tenant.system(
                a,
                c -> {
                    assertThat(db.queryForObject("select published from outbox", Boolean.class)).isFalse();
                    assertThat(db.queryForObject("select attempts from outbox", Integer.class)).isEqualTo(1);
                    return null;
                });
    }

    @Test
    void suspensionWaitsForInFlightTransactionAndThenDenies() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var request =
                    pool.submit(
                            () ->
                                    tenant.user(
                                            a,
                                            identity,
                                            false,
                                            c -> {
                                                entered.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new IllegalStateException();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                                return true;
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var suspend =
                    pool.submit(
                            () ->
                                    tenant.tx.execute(
                                            st -> {
                                                tenant.fence(a, true);
                                                return db.update("update tenants set state='SUSPENDED' where id=?", a);
                                            }));
            assertThat(suspend.isDone()).isFalse();
            release.countDown();
            assertThat(request.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(suspend.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> tenant.user(a, identity, false, c -> true))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }

    @Test
    void deletionFencesWorkersAndRetentionPurgesBusinessData() {
        db.update(
                "update tenants set state='DEPROVISIONING',delete_after=now()-interval '1 day' where id=?",
                a);
        assertThat(
                tenant.<Object>system(
                        a,
                        c -> {
                            throw new AssertionError("Must not execute deprovisioning work");
                        }))
                .isNull();
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource()));
        new Retention(db, tenant, manager).purge();
        assertThat(db.queryForObject("select state from tenants where id=?", String.class, a))
                .isEqualTo("DELETED");
        tenant.tx.executeWithoutResult(
                st -> {
                    tenant.scope(a);
                    assertThat(db.queryForList("select * from projects")).isEmpty();
                });
        assertThat(
                db.queryForObject("select active from memberships where tenant_id=?", Boolean.class, a))
                .isFalse();
    }

    @Test
    void signedBillingWebhookDeduplicatesAndRejectsForgery() throws Exception {
        String body =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                Map.of("eventId", "evt-" + a, "tenantId", a, "status", "CANCELLED"));
        long timestamp = java.time.Instant.now().getEpochSecond();
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        "test-billing-secret-32-bytes-minimum"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "HmacSHA256"));
        String signature =
                HexFormat.of()
                        .formatHex(
                                mac.doFinal(
                                        (timestamp + "." + body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            post("/api/v1/billing/webhook")
                                    .with(
                                            jwt()
                                                    .jwt(
                                                            j ->
                                                                    j.subject("provider")
                                                                            .claim(
                                                                                    "realm_access",
                                                                                    Map.of("roles", List.of("PLATFORM_ADMIN")))))
                                    .header("X-Timestamp", timestamp)
                                    .header("X-Signature", signature)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isOk());
        assertThat(
                db.queryForObject(
                        "select count(*) from billing_events where tenant_id=?", Integer.class, a))
                .isEqualTo(1);
        mvc.perform(
                        post("/api/v1/billing/webhook")
                                .with(
                                        jwt()
                                                .jwt(
                                                        j ->
                                                                j.subject("provider")
                                                                        .claim(
                                                                                "realm_access",
                                                                                Map.of("roles", List.of("PLATFORM_ADMIN")))))
                                .header("X-Timestamp", timestamp)
                                .header("X-Signature", "00")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
    }

    DurableWork durable(org.springframework.kafka.core.KafkaTemplate<String, String> kafka) {
        var work =
                new DurableWork(
                        db,
                        tenant,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        kafka,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        false);
        work.exports = streaming;
        return work;
    }

    @Test
    void durableLeasesSurviveProcessLossAndFenceStaleAttempts() {
        UUID job = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update("insert into jobs(tenant_id,id,kind) values(?,?,'REPORT')", a, job);
                    return null;
                });
        var work =
                durable(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        var first = work.claimJob(a);
        assertThat(first).isNotNull();
        assertThat(work.claimJob(a)).isNull();
        tenant.system(
                a,
                c -> {
                    db.update("update jobs set lease_until=now()-interval '1 second' where id=?", job);
                    return null;
                });
        var second = work.claimJob(a);
        assertThat(second.get("lease")).isNotEqualTo(first.get("lease"));
        work.failJob(a, job, (UUID) first.get("lease"));
        tenant.system(
                a,
                c -> {
                    assertThat(db.queryForObject("select state from jobs where id=?", String.class, job))
                            .isEqualTo("RUNNING");
                    assertThat(db.queryForObject("select attempts from jobs where id=?", Integer.class, job))
                            .isEqualTo(2);
                    return null;
                });
        work.failJob(a, job, (UUID) second.get("lease"));
        tenant.system(
                a,
                c -> {
                    assertThat(db.queryForObject("select state from jobs where id=?", String.class, job))
                            .isEqualTo("PENDING");
                    db.update("update jobs set next_attempt_at=now() where id=?", job);
                    return null;
                });
        work.runOne(a);
        tenant.system(
                a,
                c -> {
                    assertThat(db.queryForObject("select state from jobs where id=?", String.class, job))
                            .isEqualTo("DONE");
                    assertThat(
                            db.queryForObject("select result::text from jobs where id=?", String.class, job))
                            .contains("OPEN")
                            .doesNotContain(b.toString());
                    return null;
                });
    }

    @Test
    void poisonJobAttemptsCommitAndEventuallyFail() {
        UUID job = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update("insert into jobs(tenant_id,id,kind) values(?,?,'REPORT')", a, job);
                    return null;
                });
        var work =
                durable(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        for (int i = 0; i < 3; i++) {
            var claimed = work.claimJob(a);
            assertThat(claimed).isNotNull();
            work.failJob(a, job, (UUID) claimed.get("lease"));
            tenant.system(
                    a,
                    c -> {
                        db.update("update jobs set next_attempt_at=now() where id=?", job);
                        return null;
                    });
        }
        assertThat(work.claimJob(a)).isNull();
        tenant.system(
                a,
                c -> {
                    assertThat(db.queryForObject("select state from jobs where id=?", String.class, job))
                            .isEqualTo("FAILED");
                    assertThat(db.queryForObject("select attempts from jobs where id=?", Integer.class, job))
                            .isEqualTo(3);
                    return null;
                });
    }

    @Test
    void brokerWaitDoesNotHoldDatabaseTransaction() {
        var kafka = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(
                        kafka.send(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                    org.springframework.transaction.support.TransactionSynchronizationManager
                                            .isActualTransactionActive())
                                    .isFalse();
                            return CompletableFuture.completedFuture(null);
                        });
        UUID event = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into outbox(tenant_id,id,aggregate_id,version,type,correlation)"
                                    + " values(?,?,?,1,'Dispatch',?)",
                            a,
                            event,
                            id,
                            UUID.randomUUID());
                    return null;
                });
        durable(kafka).dispatchOne(a);
        tenant.system(
                a,
                c -> {
                    assertThat(
                            db.queryForObject(
                                    "select published from outbox where id=?", Boolean.class, event))
                            .isTrue();
                    return null;
                });
    }

    @Test
    void suspendedDeliveryIsDurablyDeferredAndProcessedOnResume() throws Exception {
        UUID event = UUID.randomUUID(), correlation = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into outbox(tenant_id,id,aggregate_id,version,type,correlation)"
                                    + " values(?,?,?,1,'Deferred',?)",
                            a,
                            event,
                            id,
                            correlation);
                    return null;
                });
        db.update("update tenants set state='SUSPENDED' where id=?", a);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var consumer = new EventConsumer(tenant, db, json);
        String envelope =
                json.writeValueAsString(
                        Map.of(
                                "tenant_id",
                                a,
                                "id",
                                event,
                                "aggregate_id",
                                id,
                                "version",
                                1,
                                "type",
                                "Deferred",
                                "correlation",
                                correlation));
        consumer.accept(envelope);
        tenant.delivery(
                a,
                c -> {
                    assertThat(
                            db.queryForObject("select processed from inbox where id=?", Boolean.class, event))
                            .isFalse();
                    assertThat(db.queryForList("select * from event_projection")).isEmpty();
                    return null;
                });
        db.update("update tenants set state='ACTIVE' where id=?", a);
        consumer.accept(envelope);
        consumer.accept(envelope);
        tenant.system(
                a,
                c -> {
                    assertThat(
                            db.queryForObject(
                                    "select events from event_projection where id='Deferred'", Long.class))
                            .isEqualTo(1);
                    return null;
                });
    }

    @Test
    void idempotencyAndOptimisticEditingAreTenantScoped() throws Exception {
        String body = "{\"name\":\"idempotent\",\"status\":\"OPEN\"}";
        String key = "key-" + UUID.randomUUID();
        String resource = null;
        for (int i = 0; i < 2; i++) {
            var result =
                    mvc.perform(
                                    post("/api/v1/projects")
                                            .header("X-Tenant-ID", a)
                                            .header("Idempotency-Key", key)
                                            .with(jwt().jwt(j -> j.subject(user)))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(body))
                            .andExpect(status().isCreated())
                            .andReturn();
            String received =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(result.getResponse().getContentAsString())
                            .path("id")
                            .asText();
            if (resource == null) resource = received;
            else assertThat(received).isEqualTo(resource);
        }
        mvc.perform(
                        post("/api/v1/projects")
                                .header("X-Tenant-ID", b)
                                .header("Idempotency-Key", key)
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/api/v1/projects")
                                .header("X-Tenant-ID", a)
                                .header("Idempotency-Key", key)
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.replace("idempotent", "different")))
                .andExpect(status().isConflict());
        for (int expected : List.of(200, 412))
            mvc.perform(
                            patch("/api/v1/projects/" + resource)
                                    .header("X-Tenant-ID", a)
                                    .header("If-Match", "\"0\"")
                                    .with(jwt().jwt(j -> j.subject(user)))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().is(expected));
    }

    @Test
    void strictStorageQuotaRollsBackAndIsReleasedOnDelete() {
        long old =
                db.queryForObject("select storage_bytes from plans where name='BUSINESS'", Long.class);
        try {
            db.update("update tenants set plan='BUSINESS' where id=?", a);
            db.update("update plans set storage_bytes=150 where name='BUSINESS'");
            assertThatThrownBy(
                    () ->
                            projects.create(
                                    identity, a, new ProjectApi.ProjectInput("x".repeat(100), "OPEN")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            tenant.system(
                    a,
                    c -> {
                        assertThat(db.queryForObject("select count(*) from projects", Integer.class))
                                .isEqualTo(1);
                        db.update("delete from projects");
                        assertThat(db.queryForObject("select bytes from usage_storage", Long.class)).isZero();
                        return null;
                    });
            projects.create(identity, a, new ProjectApi.ProjectInput("fits", "OPEN"));
        } finally {
            db.update("update plans set storage_bytes=? where name='BUSINESS'", old);
        }
    }

    @Test
    void billingRestrictionAllowsOwnerRecovery() throws Exception {
        db.update("update tenants set subscription='CANCELLED' where id=?", a);
        mvc.perform(
                        post("/api/v1/projects")
                                .header("X-Tenant-ID", a)
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"blocked\",\"status\":\"OPEN\"}"))
                .andExpect(status().isPaymentRequired());
        mvc.perform(
                        post("/api/v1/tenants/" + a + "/subscription")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"plan\":\"STARTER\"}"))
                .andExpect(status().isOk());
        tenant.user(
                a,
                identity,
                true,
                c -> {
                    assertThat(db.queryForList("select * from billing_history")).hasSize(1);
                    return null;
                });
    }

    @Test
    void featureVersionAndRolloutCannotEnableForeignTenant() throws Exception {
        String route = "/api/v1/tenants/" + a + "/features/export";
        mvc.perform(
                        put(route)
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true,\"rollout\":0,\"version\":0}"))
                .andExpect(status().isOk());
        tenant.user(
                a,
                identity,
                false,
                c -> {
                    var api =
                            new OperationsApi(
                                    db, tenant, new Mutations(db, new com.fasterxml.jackson.databind.ObjectMapper()));
                    assertThat(api.enabled(c, "export")).isFalse();
                    return null;
                });
        tenant.user(
                b,
                identity,
                false,
                c -> {
                    assertThat(db.queryForList("select * from feature_flags")).isEmpty();
                    return null;
                });
        mvc.perform(
                        put(route)
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true,\"rollout\":100,\"version\":7}"))
                .andExpect(status().isConflict());
    }

    @Test
    void admissionRejectsHotTenantWithoutConsumingAnotherTenantSlot() throws Exception {
        var admission = new Admission(12, 1, 0);
        try (var first = admission.acquire(a)) {
            assertThatThrownBy(() -> admission.acquire(a))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            try (var other = admission.acquire(b)) {
                assertThat(other).isNotNull();
            }
        }
        try (var released = admission.acquire(a)) {
            assertThat(released).isNotNull();
        }
    }

    @Test
    void trialExpiryAndPeriodCancellationGenerateBillingHistory() {
        db.update("update tenants set trial_ends_at=now()-interval '1 second' where id=?", a);
        assertThatThrownBy(() -> tenant.user(a, identity, true, c -> true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var work =
                durable(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        work.subscriptions();
        assertThat(db.queryForObject("select subscription from tenants where id=?", String.class, a))
                .isEqualTo("SUSPENDED");
        db.update(
                "update tenants set"
                        + " subscription='ACTIVE',cancel_at_period_end=true,cancel_effective_at=now()-interval"
                        + " '1 second' where id=?",
                a);
        work.subscriptions();
        tenant.user(
                a,
                identity,
                false,
                c -> {
                    assertThat(
                            db.queryForObject("select subscription from tenants where id=?", String.class, a))
                            .isEqualTo("CANCELLED");
                    assertThat(db.queryForList("select * from billing_history")).hasSize(2);
                    return null;
                });
    }

    @Test
    void ownerTransferAndMemberSuspensionPreserveTenantBoundaries() throws Exception {
        String next = "next-" + a;
        db.update("insert into memberships values(?,?,'TENANT_MEMBER',true)", a, next);
        mvc.perform(
                        post("/api/v1/tenants/" + a + "/owner")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        new com.fasterxml.jackson.databind.ObjectMapper()
                                                .writeValueAsString(Map.of("subject", next))))
                .andExpect(status().isOk());
        assertThat(db.queryForObject("select owner_subject from tenants where id=?", String.class, a))
                .isEqualTo(next);
        mvc.perform(
                        patch("/api/v1/tenants/" + a + "/members/" + user + "/state")
                                .with(jwt().jwt(j -> j.subject(next)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"active\":false}"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> tenant.user(a, identity, false, c -> true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tenant.user(b, identity, false, c -> true);
    }

    @Test
    void reinvitationAppliesDowngradedRoleWithoutDowngradingOwner() throws Exception {
        String recipient = "returning-" + a;
        db.update("insert into memberships values(?,?,'TENANT_ADMIN',false)", a, recipient);
        UUID invitation = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into invitations(tenant_id,id,subject,role) values(?,?,?,'TENANT_VIEWER')",
                            a,
                            invitation,
                            recipient);
                    return null;
                });
        mvc.perform(
                        post("/api/v1/invitations/" + invitation + "/accept")
                                .header("X-Tenant-ID", a)
                                .with(jwt().jwt(j -> j.subject(recipient))))
                .andExpect(status().isOk());
        assertThat(
                db.queryForObject(
                        "select role from memberships where tenant_id=? and subject=?",
                        String.class,
                        a,
                        recipient))
                .isEqualTo("TENANT_VIEWER");
    }

    @Test
    void readsShareFencesWhileSuspensionStillWaits() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            Callable<Boolean> read =
                    () ->
                            tenant.user(
                                    a,
                                    identity,
                                    false,
                                    c -> {
                                        entered.countDown();
                                        try {
                                            if (!release.await(5, TimeUnit.SECONDS))
                                                throw new IllegalStateException("Readers serialized");
                                        } catch (InterruptedException e) {
                                            throw new IllegalStateException(e);
                                        }
                                        return true;
                                    });
            var one = pool.submit(read);
            var two = pool.submit(read);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var suspend =
                    pool.submit(
                            () ->
                                    tenant.tx.execute(
                                            st -> {
                                                tenant.fence(a, true);
                                                return db.update("update tenants set state='SUSPENDED' where id=?", a);
                                            }));
            assertThat(suspend.isDone()).isFalse();
            release.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(suspend.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    @Test
    void revocationBetweenReservationAndBusinessTransactionFailsClosed() {
        var ds = Objects.requireNonNull(db.getDataSource());
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds) {
                    @Override
                    protected void doCommit(
                            org.springframework.transaction.support.DefaultTransactionStatus status) {
                        super.doCommit(status);
                        if (first.getAndSet(false)) {
                            try (var connection = ds.getConnection();
                                 var statement =
                                         connection.prepareStatement(
                                                 "update memberships set active=false where tenant_id=? and subject=?")) {
                                statement.setObject(1, a);
                                statement.setString(2, user);
                                statement.executeUpdate();
                            } catch (java.sql.SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    }
                };
        var boundary =
                new TenantTransactions(
                        db,
                        manager,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        new Admission(10, 6, 50));
        assertThatThrownBy(
                () ->
                        boundary.user(
                                a,
                                identity,
                                false,
                                c -> {
                                    throw new AssertionError("Revoked actor reached business work");
                                }))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void reservationCountsAcceptedAttemptsEvenWhenBusinessWorkRollsBack() {
        assertThatThrownBy(
                () ->
                        tenant.user(
                                a,
                                identity,
                                true,
                                c -> {
                                    throw ApiError.conflict("rollback");
                                }))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tenant.tx.executeWithoutResult(
                st -> {
                    tenant.scope(a);
                    assertThat(
                            db.queryForObject(
                                    "select api from usage where period=date_trunc('month',now())::date",
                                    Long.class))
                            .isEqualTo(1L);
                });
    }

    @Test
    void shortReservationEnforcesStrictConcurrentRateLimit() throws Exception {
        long previous =
                db.queryForObject("select api_rps from plans where name='BUSINESS'", Long.class);
        try {
            db.update("update tenants set plan='BUSINESS' where id=?", a);
            db.update("update plans set api_rps=3 where name='BUSINESS'");
            try (var pool = Executors.newFixedThreadPool(8)) {
                var futures = new ArrayList<Future<String>>();
                for (int n = 0; n < 20; n++)
                    futures.add(
                            pool.submit(
                                    () ->
                                            tenant.tx.execute(
                                                    st ->
                                                            db.queryForObject(
                                                                    "select reserve_api_request(?,?)", String.class, a, user))));
                for (var f : futures) assertThat(f.get(5, TimeUnit.SECONDS)).isIn("OK", "RATE");
            }
            tenant.tx.executeWithoutResult(
                    st -> {
                        tenant.scope(a);
                        assertThat(db.queryForObject("select window_count from usage", Integer.class))
                                .isLessThanOrEqualTo(3);
                    });
        } finally {
            db.update("update plans set api_rps=? where name='BUSINESS'", previous);
        }
    }

    @Test
    void exhaustedApiQuotaDoesNotPreventOwnerBillingRecovery() throws Exception {
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into usage(tenant_id,api) values(?,10000) on conflict(tenant_id,period) do"
                                    + " update set api=10000",
                            a);
                    return null;
                });
        assertThatThrownBy(() -> tenant.user(a, identity, false, c -> true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(
                        post("/api/v1/tenants/" + a + "/subscription")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"plan\":\"BUSINESS\"}"))
                .andExpect(status().isOk());
        tenant.user(a, identity, false, c -> true);
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedEvent(
            String body, long timestamp) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        "test-billing-secret-32-bytes-minimum"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "HmacSHA256"));
        String signature =
                HexFormat.of()
                        .formatHex(
                                mac.doFinal(
                                        (timestamp + "." + body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return post("/api/v1/billing/webhook")
                .with(
                        jwt()
                                .jwt(
                                        j ->
                                                j.subject("provider")
                                                        .claim("realm_access", Map.of("roles", List.of("PLATFORM_ADMIN")))))
                .header("X-Timestamp", timestamp)
                .header("X-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void conflictingAndOutOfOrderBillingEventsCannotReactivateSubscription() throws Exception {
        long now = java.time.Instant.now().getEpochSecond();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String event = "event-" + a;
        String cancelled =
                json.writeValueAsString(
                        Map.of("tenantId", a, "eventId", event, "status", "CANCELLED", "occurredAt", now - 5));
        mvc.perform(signedEvent(cancelled, now)).andExpect(status().isOk());
        String conflict =
                json.writeValueAsString(
                        Map.of("tenantId", a, "eventId", event, "status", "ACTIVE", "occurredAt", now - 5));
        mvc.perform(signedEvent(conflict, now)).andExpect(status().isConflict());
        String older =
                json.writeValueAsString(
                        Map.of(
                                "tenantId",
                                a,
                                "eventId",
                                event + "-old",
                                "status",
                                "ACTIVE",
                                "occurredAt",
                                now - 10));
        mvc.perform(signedEvent(older, now)).andExpect(status().isOk());
        assertThat(db.queryForObject("select subscription from tenants where id=?", String.class, a))
                .isEqualTo("CANCELLED");
        mvc.perform(signedEvent(cancelled, now + 1)).andExpect(status().isOk());
    }

    @Test
    void expiredIdempotencyKeysHaveDefinedReplayHorizon() {
        var m = new Mutations(db, new com.fasterxml.jackson.databind.ObjectMapper());
        tenant.user(
                a,
                identity,
                true,
                c -> {
                    Object one =
                            m.once(c, "expired-test", "create", Map.of("name", "x"), () -> Map.of("id", 1));
                    assertThat(one).isNotNull();
                    db.update(
                            "update idempotency set expires_at=now()-interval '1 second' where"
                                    + " id='expired-test'");
                    Object two =
                            m.once(c, "expired-test", "create", Map.of("name", "x"), () -> Map.of("id", 2));
                    assertThat(two).isEqualTo(Map.of("id", 2));
                    return null;
                });
    }

    @Test
    void mainReadTransactionIsReadOnlyAndUnfencedControlChangesFailFast() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var request =
                    pool.submit(
                            () ->
                                    tenant.user(
                                            a,
                                            identity,
                                            false,
                                            c -> {
                                                assertThat(db.queryForObject("show transaction_read_only", String.class))
                                                        .isEqualTo("on");
                                                entered.countDown();
                                                try {
                                                    release.await(5, TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                                return true;
                                            }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> db.update("update tenants set state='SUSPENDED' where id=?", a))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally {
                release.countDown();
            }
            assertThat(request.get(5, TimeUnit.SECONDS)).isTrue();
        }
        tenant.user(a, identity, false, c -> true);
    }

    @Test
    void queryCacheInvalidatesOnCommittedBulkAndTaskChanges() {
        var before = projects.list(identity, a, "", 0, 25).toString();
        assertThat(before).contains("tasks=0");
        tenant.systemExclusive(
                a,
                c -> {
                    db.update(
                            "insert into tasks(tenant_id,id,project_id,title,status) values(?,?,?,'new','TODO')",
                            a,
                            UUID.randomUUID(),
                            id);
                    return null;
                });
        var after = projects.list(identity, a, "", 0, 25).toString();
        assertThat(after).contains("tasks=1");
        assertThat(projects.list(identity, b, "", 0, 25).toString()).contains("tasks=0");
        tenant.user(
                a,
                identity,
                true,
                c -> {
                    db.update("update projects set status='CLOSED'");
                    return null;
                });
        assertThat(projects.list(identity, a, "", 0, 25).toString()).contains("CLOSED");
    }

    @Test
    void idempotencyFingerprintsDoNotDependOnJsonMapOrder() {
        var first = new Mutations(db, new com.fasterxml.jackson.databind.ObjectMapper());
        var second = new Mutations(db, new com.fasterxml.jackson.databind.ObjectMapper());
        var one = new LinkedHashMap<String, Object>();
        one.put("z", 1);
        one.put("a", 2);
        var two = new LinkedHashMap<String, Object>();
        two.put("a", 2);
        two.put("z", 1);
        tenant.user(
                a,
                identity,
                true,
                c -> {
                    first.once(c, "canonical-key", "route", one, () -> Map.of("id", 123));
                    Object replay =
                            second.once(
                                    c,
                                    "canonical-key",
                                    "route",
                                    two,
                                    () -> {
                                        throw new AssertionError("Equivalent request replay executed twice");
                                    });
                    assertThat(replay.toString()).contains("123");
                    return null;
                });
    }

    QuotaBudgets leased() {
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource()));
        var budgets =
                new QuotaBudgets(
                        db,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        manager,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        budgets.enabled = true;
        return budgets;
    }

    @Test
    void activeExpiredBudgetClosesAtomicallyWithItsReplacement() {
        var budgets = leased();
        var first = budgets.prepare(a, user);
        budgets.invalidate(a);
        budgets.flush(false);
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(
                            db.queryForObject(
                                    "select closed from api_budgets where id=?", Boolean.class, first.lease()))
                            .isFalse();
                    return null;
                });
        var replacement = budgets.prepare(a, user);
        assertThat(replacement.lease()).isNotEqualTo(first.lease());
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(
                            db.queryForObject(
                                    "select returned from api_budgets where id=?", Integer.class, first.lease()))
                            .isEqualTo(15);
                    return null;
                });
        budgets.flush(true);
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(2);
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(2);
                    return null;
                });
    }

    @Test
    void planChangeRefreshesCachedBudgetBeforeExecutingBusinessWorkOnce() {
        var budgets = leased();
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource()));
        var boundary =
                new TenantTransactions(
                        db,
                        manager,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        new Admission(12, 2, 0),
                        budgets);
        boundary.user(a, identity, false, c -> true);
        tenant.system(
                a,
                c -> {
                    db.update("update tenants set plan='STARTER' where id=?", a);
                    return null;
                });
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        int changed =
                boundary.user(
                        a,
                        identity,
                        true,
                        c -> {
                            calls.incrementAndGet();
                            return db.update("update projects set name='After plan change'");
                        });
        assertThat(changed).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
        budgets.flush(true);
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(2);
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(2);
                    return null;
                });
    }

    @Test
    void coldBudgetOwnerBatchesWithoutAnArrivalRateWarmup() {
        var budgets = leased();
        var ticket = budgets.prepare(a, user);
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(
                            db.queryForObject(
                                    "select granted from api_budgets where id=?", Integer.class, ticket.lease()))
                            .isEqualTo(16);
                    return null;
                });
        budgets.flush(true);
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(1);
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(1);
                    return null;
                });
    }

    @Test
    void forgedCachedSelectorIsDeniedBeforeOccupyingTheVictimsTenantLane() throws Exception {
        var budgets = leased();
        var admission = new Admission(12, 1, 0);
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource()));
        var boundary =
                new TenantTransactions(
                        db,
                        manager,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        admission,
                        budgets);
        boundary.user(a, identity, false, c -> true);
        var attacker =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject("not-a-member")
                        .build();
        try (var occupied = admission.verified(a)) {
            assertThatThrownBy(
                    () ->
                            boundary.user(
                                    a,
                                    attacker,
                                    false,
                                    c -> {
                                        throw new AssertionError("Foreign tenant work executed");
                                    }))
                    .isInstanceOfSatisfying(
                            org.springframework.web.server.ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        }
        budgets.flush(true);
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(1);
                    return null;
                });
    }

    @Test
    void budgetCreditsReconcileUnusedReservationsAndCountAcceptedReads() {
        var budgets = leased();
        for (int i = 0; i < 5; i++) budgets.admit(a, user);
        budgets.flush(true);
        tenant.tx.executeWithoutResult(
                st -> {
                    tenant.scope(a);
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(5);
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(5);
                });
    }

    @Test
    void everyLocalBudgetTokenRequiresFreshMembership() {
        var budgets = leased();
        budgets.admit(a, user);
        budgets.admit(a, user);
        db.update("update memberships set active=false where tenant_id=? and subject=?", a, user);
        assertThatThrownBy(() -> budgets.admit(a, user))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        budgets.flush(true);
        tenant.tx.executeWithoutResult(
                st -> {
                    tenant.scope(a);
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(2);
                });
    }

    @Test
    void independentBudgetOwnersCannotExceedMonthlyQuota() throws Exception {
        long old =
                db.queryForObject("select api_requests from plans where name='BUSINESS'", Long.class);
        db.update("update tenants set plan='BUSINESS' where id=?", a);
        db.update("update plans set api_requests=10 where name='BUSINESS'");
        var one = leased();
        var two = leased();
        try {
            int accepted = 0;
            try (var pool = Executors.newFixedThreadPool(6)) {
                var futures = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < 30; i++) {
                    QuotaBudgets budget = i % 2 == 0 ? one : two;
                    futures.add(
                            pool.submit(
                                    () -> {
                                        try {
                                            budget.admit(a, user);
                                            return true;
                                        } catch (org.springframework.web.server.ResponseStatusException e) {
                                            assertThat(e.getStatusCode().value()).isEqualTo(429);
                                            return false;
                                        }
                                    }));
                }
                for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) accepted++;
            }
            assertThat(accepted).isBetween(1, 10);
            one.flush(true);
            two.flush(true);
            final int total = accepted;
            tenant.tx.executeWithoutResult(
                    st -> {
                        tenant.scope(a);
                        assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(total);
                        assertThat(db.queryForObject("select api_confirmed from usage", Long.class))
                                .isEqualTo(total);
                    });
        } finally {
            db.update("update plans set api_requests=? where name='BUSINESS'", old);
        }
    }

    @Test
    void budgetGrantsRemainInsidePerSecondLimit() throws Exception {
        long old = db.queryForObject("select api_rps from plans where name='BUSINESS'", Long.class);
        db.update("update tenants set plan='BUSINESS' where id=?", a);
        db.update("update plans set api_rps=3 where name='BUSINESS'");
        try {
            try (var pool = Executors.newFixedThreadPool(8)) {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 12; i++)
                    futures.add(
                            pool.submit(
                                    () ->
                                            tenant.tx.execute(
                                                    st ->
                                                            db.queryForObject(
                                                                    "select grant_api_budget(?,?,?,?,?,?)::text",
                                                                    String.class,
                                                                    a,
                                                                    user,
                                                                    4,
                                                                    UUID.randomUUID(),
                                                                    null,
                                                                    0))));
                for (var f : futures) f.get(5, TimeUnit.SECONDS);
            }
            tenant.tx.executeWithoutResult(
                    st -> {
                        tenant.scope(a);
                        for (var row :
                                db.queryForList(
                                        "select sum(granted) as granted from api_budgets group by window_at"))
                            assertThat(((Number) row.get("granted")).intValue()).isLessThanOrEqualTo(3);
                    });
        } finally {
            db.update("update plans set api_rps=? where name='BUSINESS'", old);
        }
    }

    @Test
    void lostBudgetOwnerCannotCauseOverspendAndClosingIsTenantScopedAndIdempotent() {
        long old =
                db.queryForObject("select api_requests from plans where name='BUSINESS'", Long.class);
        db.update("update tenants set plan='BUSINESS' where id=?", a);
        db.update("update plans set api_requests=4 where name='BUSINESS'");
        UUID lease = UUID.randomUUID();
        try {
            String grant =
                    tenant.tx.execute(
                            st ->
                                    db.queryForObject(
                                            "select grant_api_budget(?,?,?,?,?,?)::text",
                                            String.class,
                                            a,
                                            user,
                                            4,
                                            lease,
                                            null,
                                            0));
            assertThat(grant).contains("OK");
            // An owner disappears without confirming how many local tokens it used. Never guess a refund.
            String next =
                    tenant.tx.execute(
                            st ->
                                    db.queryForObject(
                                            "select grant_api_budget(?,?,?,?,?,?)::text",
                                            String.class,
                                            a,
                                            user,
                                            4,
                                            UUID.randomUUID(),
                                            null,
                                            0));
            assertThat(next).contains("MONTHLY");
            tenant.tx.executeWithoutResult(
                    st -> {
                        tenant.scope(b);
                        assertThat(db.queryForObject("select count(*) from api_budgets", Integer.class))
                                .isZero();
                        assertThat(
                                db.queryForObject("select close_api_budget(?,?,?)", Boolean.class, b, lease, 2))
                                .isFalse();
                    });
            tenant.tx.executeWithoutResult(
                    st -> {
                        assertThat(
                                db.queryForObject("select close_api_budget(?,?,?)", Boolean.class, a, lease, 2))
                                .isTrue();
                        assertThat(
                                db.queryForObject("select close_api_budget(?,?,?)", Boolean.class, a, lease, 2))
                                .isFalse();
                        assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(2);
                        assertThat(db.queryForObject("select api_confirmed from usage", Long.class))
                                .isEqualTo(2);
                    });
        } finally {
            db.update("update plans set api_requests=? where name='BUSINESS'", old);
        }
    }

    @Test
    void forgedTenantSelectorsCannotConsumeTheVictimsAdmissionLane() throws Exception {
        var admission = new Admission(12, 1, 0);
        try (var attacker = admission.acquire(a, "intruder")) {
            try (var victim = admission.acquire(a, user)) {
                assertThat(victim).isNotNull();
            }
        }
        try (var verified = admission.verified(a)) {
            assertThatThrownBy(() -> admission.verified(a))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            try (var unrelated = admission.verified(b)) {
                assertThat(unrelated).isNotNull();
            }
        }
    }

    @Test
    void reconciliationPreservesUnknownUsageAndAcceptsLateProofExactlyOnce() {
        UUID lease = UUID.randomUUID();
        tenant.tx.executeWithoutResult(
                st -> {
                    db.queryForObject(
                            "select grant_api_budget(?,?,?,?,?,?)::text",
                            String.class,
                            a,
                            user,
                            4,
                            lease,
                            null,
                            0);
                    db.update(
                            "update api_budgets set window_at=clock_timestamp()-interval '90 seconds' where id=?",
                            lease);
                    String first =
                            db.queryForObject(
                                    "select reconcile_api_budgets(?,?,?)::text", String.class, a, "system:test", 100);
                    assertThat(first).contains("\"settled_grants\": 1").contains("\"refunded_credits\": 0");
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(4);
                    assertThat(db.queryForObject("select api_assumed from usage", Long.class)).isEqualTo(3);
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(1);
                    String duplicate =
                            db.queryForObject(
                                    "select reconcile_api_budgets(?,?,?)::text", String.class, a, "system:test", 100);
                    assertThat(duplicate).contains("\"settled_grants\": 0");
                    assertThat(
                            db.queryForObject("select close_api_budget(?,?,?)", Boolean.class, a, lease, 2))
                            .isTrue();
                    assertThat(
                            db.queryForObject("select close_api_budget(?,?,?)", Boolean.class, a, lease, 2))
                            .isFalse();
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(2);
                    assertThat(db.queryForObject("select api_assumed from usage", Long.class)).isZero();
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(2);
                });
    }

    @Test
    void quotaRestorePreservesProvenAndAssumedEvidenceExactly() {
        UUID proven = UUID.randomUUID(), assumed = UUID.randomUUID();
        tenant.tx.executeWithoutResult(
                st -> {
                    for (UUID lease : List.of(proven, assumed))
                        db.queryForObject(
                                "select grant_api_budget(?,?,?,?,?,?)::text",
                                String.class,
                                a,
                                user,
                                4,
                                lease,
                                null,
                                0);
                    db.queryForObject("select close_api_budget(?,?,?)", Boolean.class, a, proven, 2);
                    db.update(
                            "update api_budgets set window_at=clock_timestamp()-interval '90 seconds' where id=?",
                            assumed);
                    db.queryForObject(
                            "select reconcile_api_budgets(?,?,?)::text", String.class, a, "system:test", 100);
                    String archive =
                            db.queryForObject(
                                    "select jsonb_agg(to_jsonb(b) order by id)::text from api_budgets b",
                                    String.class);
                    assertThat(archive).contains("ASSUMED").contains("PROVEN");
                    db.execute(
                            "create temporary table archived_budgets on commit drop as select * from"
                                    + " api_budgets");
                    db.update("delete from api_budgets");
                    db.update("insert into api_budgets select * from archived_budgets");
                    assertThat(
                            db.queryForObject(
                                    "select jsonb_agg(to_jsonb(b) order by id)::text from api_budgets b",
                                    String.class))
                            .isEqualTo(archive);
                });
    }

    @Test
    void budgetGrantAckReplayNeverReservesTwiceOrReplaysAcrossActors() {
        UUID lease = UUID.randomUUID();
        tenant.tx.executeWithoutResult(
                st -> {
                    String first =
                            db.queryForObject(
                                    "select grant_api_budget(?,?,?,?,?,?)::text",
                                    String.class,
                                    a,
                                    user,
                                    4,
                                    lease,
                                    null,
                                    0);
                    assertThat(first).contains("OK");
                    String replay =
                            db.queryForObject(
                                    "select grant_api_budget(?,?,?,?,?,?)::text",
                                    String.class,
                                    a,
                                    user,
                                    4,
                                    lease,
                                    null,
                                    0);
                    assertThat(replay).contains("\"replayed\": true");
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(4);
                    assertThat(db.queryForObject("select count(*) from api_budgets", Integer.class))
                            .isEqualTo(1);
                    assertThat(
                            db.queryForObject(
                                    "select grant_api_budget(?,?,?,?,?,?)::text",
                                    String.class,
                                    a,
                                    "intruder",
                                    4,
                                    lease,
                                    null,
                                    0))
                            .contains("DENIED");
                });
    }

    @Test
    void streamingExportBeyondOldLimitsUsesChunksAndPreservesIsolation() throws Exception {
        db.update("update tenants set plan='BUSINESS' where id=?", a);
        UUID job = UUID.randomUUID();
        tenant.systemExclusive(
                a,
                c -> {
                    db.update(
                            "insert into projects(tenant_id,id,name) select ?,gen_random_uuid(),'bulk-'||i from"
                                    + " generate_series(1,1200)i",
                            a);
                    db.update(
                            "insert into tasks(tenant_id,id,project_id,title) select"
                                    + " ?,gen_random_uuid(),?,'task-'||i from generate_series(1,12000)i",
                            a,
                            id);
                    db.update(
                            "insert into jobs(tenant_id,id,kind,requested_by) values(?,?,'EXPORT',?)",
                            a,
                            job,
                            user);
                    return null;
                });
        var worker =
                durable(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        worker.runOne(a);
        var response =
                mvc.perform(
                                get("/api/v1/jobs/" + job + "/content")
                                        .with(jwt().jwt(j -> j.subject(user)))
                                        .header("X-Tenant-ID", a))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        byte[] bytes = response.getContentAsByteArray();
        assertThat(response.getHeader("ETag"))
                .isEqualTo(
                        "\""
                                + java.util.HexFormat.of()
                                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                                + "\"");
        try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree(gzip);
            assertThat(document.path("projects").size()).isEqualTo(1201);
            assertThat(document.path("tasks").size()).isEqualTo(12000);
            assertThat(document.toString()).doesNotContain(b.toString());
        }
        mvc.perform(
                        get("/api/v1/jobs/" + job + "/content")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .header("X-Tenant-ID", b))
                .andExpect(status().isNotFound());
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(
                            db.queryForObject(
                                    "select max(octet_length(data)) from export_chunks", Integer.class))
                            .isLessThanOrEqualTo(StreamingExports.CHUNK);
                    assertThat(db.queryForObject("select count(*) from export_chunks", Integer.class))
                            .isBetween(2, 19);
                    assertThat(streaming.snapshots().getDataSourceProperties().getProperty("socketTimeout"))
                            .isEqualTo(Integer.toString(streaming.maxSeconds + 10));
                    return null;
                });
        db.update("update memberships set active=false where tenant_id=? and subject=?", a, user);
        mvc.perform(
                        get("/api/v1/jobs/" + job + "/content")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .header("X-Tenant-ID", a))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportRequiresCurrentRequesterMembershipAndFencesReplacedGeneration() throws Exception {
        UUID job = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into jobs(tenant_id,id,kind,requested_by) values(?,?,'EXPORT',?)",
                            a,
                            job,
                            user);
                    return null;
                });
        var worker =
                durable(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        var claim = worker.claimJob(a);
        UUID lease = (UUID) claim.get("lease");
        streaming.checkpoint(a, job, lease, new byte[]{1, 2}, 0);
        tenant.system(
                a,
                c -> {
                    db.update("update jobs set lease_token=? where id=?", UUID.randomUUID(), job);
                    return null;
                });
        assertThatThrownBy(() -> streaming.checkpoint(a, job, lease, new byte[]{3}, 1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(
                        get("/api/v1/jobs/" + job + "/content")
                                .with(jwt().jwt(j -> j.subject(user)))
                                .header("X-Tenant-ID", a))
                .andExpect(status().isNotFound());
        db.update("update memberships set active=false where tenant_id=? and subject=?", a, user);
        assertThatThrownBy(() -> streaming.generate(a, job, lease))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void fusedAuthorizationRejectsForgedSelectorsWithoutBurningCachedTenantCredits() {
        var budgets = leased();
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource()));
        var boundary =
                new TenantTransactions(
                        db,
                        manager,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        new Admission(12, 6, 0),
                        budgets);
        boundary.user(a, identity, false, c -> true);
        boundary.user(a, identity, false, c -> true);
        for (int i = 0; i < 12; i++)
            assertThatThrownBy(
                    () ->
                            boundary.user(
                                    a,
                                    identity("intruder"),
                                    false,
                                    c -> {
                                        throw new AssertionError("Unauthorized business query");
                                    }))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        budgets.flush(true);
        tenant.tx.executeWithoutResult(
                st -> {
                    tenant.scope(a);
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(2);
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(2);
                });
        db.update("update memberships set active=false where tenant_id=? and subject=?", a, user);
        assertThatThrownBy(() -> boundary.user(a, identity, false, c -> true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void streamingDownloadStopsAtChunkBoundaryAfterMembershipRevocation() throws Exception {
        UUID job = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        tenant.system(
                a,
                c -> {
                    db.update(
                            "insert into"
                                    + " jobs(tenant_id,id,kind,state,result,requested_by,export_generation,export_expires_at)"
                                    + " values(?,?,'EXPORT','DONE',?::jsonb,?,?,clock_timestamp()+interval '1 hour')",
                            a,
                            job,
                            "{\"compressed_bytes\":4,\"chunks\":2,\"sha256\":\"test\"}",
                            user,
                            generation);
                    db.update(
                            "insert into export_chunks values(?,?,?,0,?)", a, job, generation, new byte[]{1, 2});
                    db.update(
                            "insert into export_chunks values(?,?,?,1,?)", a, job, generation, new byte[]{3, 4});
                    return null;
                });
        var output = new java.io.ByteArrayOutputStream();
        var response =
                new org.springframework.mock.web.MockHttpServletResponse() {
                    public jakarta.servlet.ServletOutputStream getOutputStream() {
                        return new jakarta.servlet.ServletOutputStream() {
                            public boolean isReady() {
                                return true;
                            }

                            public void setWriteListener(jakarta.servlet.WriteListener l) {
                            }

                            public void write(int value) {
                                output.write(value);
                            }

                            public void write(byte[] data, int offset, int length) {
                                output.write(data, offset, length);
                                db.update(
                                        "update memberships set active=false where tenant_id=? and subject=?", a, user);
                            }
                        };
                    }
                };
        var downloads =
                new ExportDownloads(tenant, db, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThatThrownBy(() -> downloads.download(identity, a, job, response))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(output.toByteArray()).containsExactly((byte) 1, (byte) 2);
    }

    @Test
    void statementAccountingSkipsZeroDeltasAndRollsBackOversizedBulkAtomically() {
        long old =
                db.queryForObject("select storage_bytes from plans where name='BUSINESS'", Long.class);
        db.update("update tenants set plan='BUSINESS' where id=?", a);
        tenant.user(
                a,
                identity,
                true,
                c -> {
                    String version = db.queryForObject("select xmin::text from usage_storage", String.class);
                    db.update("update projects set status='ACTIVE'");
                    assertThat(db.queryForObject("select xmin::text from usage_storage", String.class))
                            .isEqualTo(version);
                    return null;
                });
        try {
            db.update("update plans set storage_bytes=400 where name='BUSINESS'");
            assertThatThrownBy(
                    () ->
                            tenant.user(
                                    a,
                                    identity,
                                    true,
                                    c ->
                                            db.update(
                                                    "insert into projects(tenant_id,id,name) select"
                                                            + " ?,gen_random_uuid(),repeat('x',80)||i from"
                                                            + " generate_series(1,3)i",
                                                    a)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            tenant.user(
                    a,
                    identity,
                    false,
                    c -> {
                        assertThat(db.queryForObject("select count(*) from projects", Integer.class))
                                .isEqualTo(1);
                        return null;
                    });
        } finally {
            db.update("update plans set storage_bytes=? where name='BUSINESS'", old);
        }
    }

    @Test
    void longLivedDownloadBoundaryRejectsExpiredTokenEvenAfterInitialAuthentication() {
        Jwt expired =
                Jwt.withTokenValue("expired")
                        .header("alg", "RS256")
                        .subject(user)
                        .expiresAt(java.time.Instant.now().minusSeconds(1))
                        .build();
        assertThatThrownBy(() -> tenant.checkedRead(a, expired, c -> true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void reconciliationAndSupportDownloadsRequireExplicitPlatformAuthorization() throws Exception {
        mvc.perform(
                        post("/api/v1/admin/tenants/" + a + "/quota/reconciliation")
                                .with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/admin/tenants/" + a + "/exports/" + UUID.randomUUID() + "/content")
                                .with(jwt().jwt(j -> j.subject(user))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/admin/tenants/" + a + "/quota/reconciliation")
                                .with(
                                        jwt()
                                                .jwt(
                                                        j ->
                                                                j.subject("support")
                                                                        .claim(
                                                                                "realm_access",
                                                                                Map.of("roles", List.of("PLATFORM_ADMIN"))))))
                .andExpect(status().isOk());
    }

    @Test
    void leasedWriteAttemptsPreserveAtomicBusinessRollbackAndExactKnownUsage() {
        var budgets = leased();
        var manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource()));
        var boundary =
                new TenantTransactions(
                        db,
                        manager,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        new Admission(12, 6, 0),
                        budgets);
        for (int i = 0; i < 2; i++)
            boundary.user(
                    a,
                    identity,
                    true,
                    c -> db.update("update projects set version=version+1 where id=?", id));
        assertThatThrownBy(
                () ->
                        boundary.user(
                                a,
                                identity,
                                true,
                                c -> {
                                    db.update("update projects set version=version+1 where id=?", id);
                                    throw new IllegalStateException("business rollback");
                                }))
                .isInstanceOf(IllegalStateException.class);
        budgets.flush(true);
        tenant.checkedRead(
                a,
                identity,
                c -> {
                    assertThat(db.queryForObject("select version from projects", Long.class)).isEqualTo(2);
                    assertThat(db.queryForObject("select api from usage", Long.class)).isEqualTo(3);
                    assertThat(db.queryForObject("select api_confirmed from usage", Long.class)).isEqualTo(3);
                    return null;
                });
    }

    @Test
    void quotaLedgerPreventsDeletingReservedCountersAndRuntimeCannotRewriteMigrations() {
        tenant.tx.executeWithoutResult(
                st ->
                        db.queryForObject(
                                "select grant_api_budget(?,?,?,?,?,?)::text",
                                String.class,
                                a,
                                user,
                                4,
                                UUID.randomUUID(),
                                null,
                                0));
        assertThatThrownBy(
                () ->
                        tenant.tx.executeWithoutResult(
                                st -> {
                                    tenant.scope(a);
                                    db.update("delete from usage");
                                }))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                () ->
                        db.update("update flyway_schema_history set success=false where installed_rank=-1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
