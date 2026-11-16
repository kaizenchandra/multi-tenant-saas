CREATE TABLE tenants
(
    id            uuid PRIMARY KEY,
    slug          varchar(63)  NOT NULL UNIQUE,
    name          varchar(160) NOT NULL,
    owner_subject varchar(200) NOT NULL,
    state         text         NOT NULL CHECK (state IN
                                               ('PENDING', 'PROVISIONING', 'ACTIVE', 'SUSPENDED', 'DEPROVISIONING',
                                                'DELETED', 'FAILED')),
    plan          text         NOT NULL DEFAULT 'FREE' CHECK (plan IN ('FREE', 'STARTER', 'BUSINESS', 'ENTERPRISE')),
    subscription  text         NOT NULL DEFAULT 'TRIALING',
    legal_hold    boolean      NOT NULL DEFAULT false,
    config        jsonb        NOT NULL DEFAULT '{}',
    created_at    timestamptz  NOT NULL DEFAULT now(),
    delete_after  timestamptz,
    UNIQUE (owner_subject, slug)
);
CREATE TABLE memberships
(
    tenant_id uuid REFERENCES tenants (id),
    subject   varchar(200),
    role      text    NOT NULL CHECK (role IN ('TENANT_OWNER', 'TENANT_ADMIN', 'TENANT_MEMBER', 'TENANT_VIEWER')),
    active    boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, subject)
);
CREATE INDEX memberships_subject ON memberships (subject, tenant_id);
CREATE TABLE plans
(
    name           text PRIMARY KEY,
    projects       int     NOT NULL,
    seats          int     NOT NULL,
    jobs           int     NOT NULL,
    api_requests   bigint  NOT NULL,
    export_enabled boolean NOT NULL
);
INSERT INTO plans
VALUES ('FREE', 5, 3, 10, 10000, false),
       ('STARTER', 100, 20, 100, 100000, true),
       ('BUSINESS', 10000, 500, 1000, 10000000, true),
       ('ENTERPRISE', 100000, 10000, 10000, 100000000, true);
CREATE TABLE billing_events
(
    id         text PRIMARY KEY,
    tenant_id  uuid REFERENCES tenants (id),
    created_at timestamptz DEFAULT now()
);
CREATE TABLE platform_audit
(
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id uuid,
    actor     text        NOT NULL,
    action    text        NOT NULL,
    at        timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE projects
(
    tenant_id uuid         NOT NULL REFERENCES tenants (id),
    id        uuid         NOT NULL,
    name      varchar(160) NOT NULL,
    status    text         NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACTIVE', 'CLOSED')),
    version   bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, name)
);
CREATE INDEX projects_status ON projects (tenant_id, status, id);
CREATE TABLE tasks
(
    tenant_id  uuid         NOT NULL,
    id         uuid         NOT NULL,
    project_id uuid         NOT NULL,
    title      varchar(200) NOT NULL,
    status     text         NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'DOING', 'DONE')),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX tasks_project ON tasks (tenant_id, project_id, id);
CREATE TABLE project_members
(
    tenant_id  uuid,
    project_id uuid,
    subject    varchar(200),
    PRIMARY KEY (tenant_id, project_id, subject),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, subject) REFERENCES memberships (tenant_id, subject)
);
CREATE TABLE task_assignments
(
    tenant_id uuid,
    task_id   uuid,
    subject   varchar(200),
    PRIMARY KEY (tenant_id, task_id, subject),
    FOREIGN KEY (tenant_id, task_id) REFERENCES tasks (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, subject) REFERENCES memberships (tenant_id, subject)
);
CREATE TABLE invitations
(
    tenant_id  uuid,
    id         uuid,
    subject    varchar(200) NOT NULL,
    role       text         NOT NULL CHECK (role IN ('TENANT_ADMIN', 'TENANT_MEMBER', 'TENANT_VIEWER')),
    expires_at timestamptz  NOT NULL DEFAULT now() + interval '7 days',
    accepted   boolean      NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, subject)
);
CREATE TABLE usage
(
    tenant_id uuid   NOT NULL,
    period date NOT NULL DEFAULT date_trunc('month',now()):: date,
    api       bigint NOT NULL DEFAULT 0,
    jobs      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, period)
);
CREATE TABLE audit
(
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    actor       text        NOT NULL,
    action      text        NOT NULL,
    resource    text        NOT NULL,
    correlation uuid        NOT NULL,
    at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX audit_time ON audit (tenant_id, at DESC);
CREATE TABLE outbox
(
    tenant_id      uuid        NOT NULL,
    id             uuid        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    version        bigint      NOT NULL,
    type           text        NOT NULL,
    schema_version int         NOT NULL DEFAULT 1,
    correlation    uuid        NOT NULL,
    at             timestamptz NOT NULL DEFAULT now(),
    published      boolean     NOT NULL DEFAULT false,
    attempts       int         NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX outbox_pending ON outbox (tenant_id, published, at);
CREATE TABLE inbox
(
    tenant_id uuid,
    id        uuid,
    at        timestamptz DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, id) REFERENCES outbox (tenant_id, id)
);
CREATE TABLE jobs
(
    tenant_id  uuid,
    id         uuid,
    kind       text        NOT NULL CHECK (kind IN ('EXPORT', 'REPORT')),
    state      text        NOT NULL DEFAULT 'PENDING',
    attempts   int         NOT NULL DEFAULT 0,
    result     jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX jobs_pending ON jobs (tenant_id, state, created_at);
DO
$$ DECLARE
t text;
BEGIN
 FOREACH
t IN ARRAY ARRAY['projects','tasks','project_members','task_assignments','invitations','usage','audit','outbox','inbox','jobs'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
EXECUTE format($policy$CREATE POLICY tenant_scope ON %I USING (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid)$policy$, t);
END LOOP;
END $$;
GRANT USAGE ON SCHEMA
public TO saas_runtime;
GRANT
SELECT,
INSERT
,
UPDATE,
DELETE
ON ALL TABLES IN SCHEMA public TO saas_runtime;
REVOKE ALL ON flyway_schema_history FROM saas_runtime;
REVOKE UPDATE, DELETE ON audit,platform_audit FROM saas_runtime;
GRANT
USAGE
ON
ALL
SEQUENCES IN SCHEMA public TO saas_runtime;
