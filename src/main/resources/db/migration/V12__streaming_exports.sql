CREATE TABLE export_chunks
(
    tenant_id  uuid  NOT NULL,
    job_id     uuid  NOT NULL,
    generation uuid  NOT NULL,
    sequence   int   NOT NULL CHECK (sequence >= 0),
    data       bytea NOT NULL CHECK (octet_length(data) <= 262144),
    PRIMARY KEY (tenant_id, job_id, generation, sequence),
    FOREIGN KEY (tenant_id, job_id) REFERENCES jobs (tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE export_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_chunks FORCE ROW LEVEL SECURITY;
CREATE
POLICY tenant_scope ON export_chunks USING(tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
GRANT SELECT, INSERT, DELETE ON export_chunks TO saas_runtime;
CREATE FUNCTION account_export_storage() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
selected uuid;delta
bigint;cap
bigint;
BEGIN
 IF
TG_OP='INSERT' THEN selected:=NEW.tenant_id;delta
:=octet_length(NEW.data);
ELSE selected:=OLD.tenant_id;delta
:=-octet_length(OLD.data);
END IF;
SELECT p.storage_bytes
INTO cap
FROM tenants t
         JOIN plans p ON p.name = t.plan
WHERE t.id = selected;
INSERT INTO usage_storage(tenant_id, bytes)
VALUES (selected, 0) ON CONFLICT DO NOTHING;
UPDATE usage_storage
SET bytes=bytes + delta
WHERE tenant_id = selected
  AND (delta <= 0 OR bytes + delta <= cap);
IF
NOT FOUND THEN RAISE EXCEPTION 'Storage quota exceeded' USING ERRCODE='23514';
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
ELSE RETURN NEW;
END IF;
END $$;
CREATE TRIGGER export_chunks_storage
    BEFORE INSERT OR
DELETE
ON export_chunks FOR EACH ROW
EXECUTE FUNCTION account_export_storage();
ALTER TABLE jobs
    ADD COLUMN requested_by text;
ALTER TABLE jobs
    ADD COLUMN export_generation uuid;
ALTER TABLE jobs
    ADD COLUMN export_expires_at timestamptz;
CREATE INDEX jobs_export_expiry ON jobs (tenant_id, export_expires_at) WHERE export_generation IS NOT NULL;
ALTER TABLE jobs
    ADD COLUMN support_export boolean NOT NULL DEFAULT false;
DO
$$ DECLARE
selected uuid;
BEGIN
FOR selected IN
SELECT id
FROM tenants LOOP PERFORM set_config('app.current_tenant',selected::text,true);
UPDATE jobs
SET requested_by=(SELECT owner_subject FROM tenants WHERE id = selected)
WHERE kind = 'EXPORT'
  AND requested_by IS NULL;
END LOOP;
 PERFORM
set_config('app.current_tenant','',true);
END $$;
