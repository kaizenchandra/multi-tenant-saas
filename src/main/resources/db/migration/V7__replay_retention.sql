ALTER TABLE billing_events
    ADD COLUMN fingerprint text;
ALTER TABLE billing_events
    ADD COLUMN occurred_at timestamptz;
ALTER TABLE tenants
    ADD COLUMN billing_event_at timestamptz;
ALTER TABLE idempotency
    ADD COLUMN expires_at timestamptz NOT NULL DEFAULT now() + interval '7 days';
CREATE INDEX idempotency_expiry ON idempotency (tenant_id, expires_at);
