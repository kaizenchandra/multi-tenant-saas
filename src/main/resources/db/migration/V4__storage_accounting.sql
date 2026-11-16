CREATE TABLE usage_storage
(
    tenant_id uuid PRIMARY KEY,
    bytes     bigint NOT NULL DEFAULT 0 CHECK (bytes >= 0)
);
ALTER TABLE usage_storage ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_storage FORCE ROW LEVEL SECURITY;
CREATE
POLICY tenant_scope ON usage_storage USING(tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON usage_storage TO saas_runtime;
-- Account for existing data with owner-scoped transactions even under FORCE RLS.
DO
$$ DECLARE
t uuid;
BEGIN
FOR t IN SELECT id FROM tenants LOOP
 PERFORM set_config('app.current_tenant',t::text,true);
INSERT INTO usage_storage
SELECT t,
       (SELECT coalesce(sum(64 + octet_length(name)), 0) FROM projects) +
       (SELECT coalesce(sum(64 + octet_length(title)), 0) FROM tasks) +
       (SELECT coalesce(sum(128 + octet_length(coalesce(result::text, ''))), 0) FROM jobs);
END LOOP; PERFORM
set_config('app.current_tenant','',true);
END $$;
CREATE FUNCTION account_storage() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
new_bytes bigint:=0;old_bytes
bigint:=0;selected
uuid;cap
bigint;changed
int;
BEGIN
 IF
TG_OP<>'DELETE' THEN
  selected:=NEW.tenant_id;
  IF
TG_TABLE_NAME='projects' THEN new_bytes:=64+octet_length(NEW.name);
  ELSIF
TG_TABLE_NAME='tasks' THEN new_bytes:=64+octet_length(NEW.title);
ELSE new_bytes:=128+octet_length(coalesce(NEW.result::text,''));
END IF;
END IF;
 IF
TG_OP<>'INSERT' THEN
  selected:=OLD.tenant_id;
  IF
TG_TABLE_NAME='projects' THEN old_bytes:=64+octet_length(OLD.name);
  ELSIF
TG_TABLE_NAME='tasks' THEN old_bytes:=64+octet_length(OLD.title);
ELSE old_bytes:=128+octet_length(coalesce(OLD.result::text,''));
END IF;
END IF;
 IF
TG_OP='UPDATE' AND NEW.tenant_id<>OLD.tenant_id THEN RAISE EXCEPTION 'Tenant reassignment prohibited' USING ERRCODE='23514';
END IF;
SELECT p.storage_bytes
INTO cap
FROM tenants t
         JOIN plans p ON p.name = t.plan
WHERE t.id = selected;
INSERT INTO usage_storage(tenant_id, bytes)
VALUES (selected, 0) ON CONFLICT DO NOTHING;
UPDATE usage_storage
SET bytes=bytes + new_bytes - old_bytes
WHERE tenant_id = selected
  AND (new_bytes <= old_bytes OR bytes + new_bytes - old_bytes <= cap);
GET DIAGNOSTICS changed= ROW_COUNT;
IF
changed<>1 THEN RAISE EXCEPTION 'Storage quota exceeded' USING ERRCODE='23514';
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
ELSE RETURN NEW;
END IF;
END $$;
CREATE TRIGGER projects_storage
    BEFORE INSERT OR
UPDATE OR
DELETE
ON projects FOR EACH ROW
    EXECUTE FUNCTION account_storage();
CREATE TRIGGER tasks_storage
    BEFORE INSERT OR
UPDATE OR
DELETE
ON tasks FOR EACH ROW
    EXECUTE FUNCTION account_storage();
CREATE TRIGGER jobs_storage
    BEFORE INSERT OR
UPDATE OR
DELETE
ON jobs FOR EACH ROW
    EXECUTE FUNCTION account_storage();
