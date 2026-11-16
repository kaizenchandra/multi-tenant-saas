ALTER TABLE jobs
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE jobs
    ADD COLUMN lease_until timestamptz;
ALTER TABLE jobs
    ADD COLUMN lease_token uuid;
ALTER TABLE jobs
    ADD COLUMN failure_code text;
ALTER TABLE outbox
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE outbox
    ADD COLUMN lease_until timestamptz;
ALTER TABLE outbox
    ADD COLUMN lease_token uuid;
ALTER TABLE inbox
    ADD COLUMN processed boolean NOT NULL DEFAULT true;
ALTER TABLE tenants
    ADD COLUMN trial_ends_at timestamptz NOT NULL DEFAULT now() + interval '14 days';
ALTER TABLE tenants
    ADD COLUMN cancel_at_period_end boolean NOT NULL DEFAULT false;
ALTER TABLE plans
    ADD COLUMN storage_bytes bigint NOT NULL DEFAULT 10485760;
UPDATE plans
SET storage_bytes=CASE name
                      WHEN 'FREE' THEN 10485760
                      WHEN 'STARTER' THEN 104857600
                      WHEN 'BUSINESS' THEN 1073741824
                      ELSE 10737418240 END;
CREATE TABLE feature_flags
(
    tenant_id uuid    NOT NULL,
    id        text    NOT NULL,
    enabled   boolean NOT NULL,
    rollout   int     NOT NULL CHECK (rollout BETWEEN 0 AND 100),
    version   bigint  NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, id)
);
CREATE TABLE idempotency
(
    tenant_id    uuid        NOT NULL,
    id           text        NOT NULL,
    actor        text        NOT NULL,
    request_hash text        NOT NULL,
    response     jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE TABLE billing_history
(
    tenant_id uuid        NOT NULL,
    id        uuid        NOT NULL,
    status    text        NOT NULL,
    plan      text        NOT NULL,
    actor     text        NOT NULL,
    at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE TABLE event_projection
(
    tenant_id uuid   NOT NULL,
    id        text   NOT NULL,
    events    bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id)
);
DO
$$ DECLARE
t text;
BEGIN
 FOREACH
t IN ARRAY ARRAY['feature_flags','idempotency','billing_history','event_projection'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
EXECUTE format($policy$CREATE POLICY tenant_scope ON %I USING (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid)$policy$, t);
EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO saas_runtime', t);
END LOOP;
END $$;
REVOKE UPDATE, DELETE ON billing_history FROM saas_runtime;
CREATE INDEX jobs_due ON jobs (tenant_id, next_attempt_at) WHERE state IN ('PENDING','RUNNING');
CREATE INDEX outbox_due ON outbox (tenant_id, next_attempt_at) WHERE NOT published;
