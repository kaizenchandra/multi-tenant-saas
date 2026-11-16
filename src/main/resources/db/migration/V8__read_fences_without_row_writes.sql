CREATE FUNCTION tenant_lock_key(selected uuid) RETURNS bigint
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
SELECT pg_catalog.hashtextextended('saas:tenant:' || selected::text, 7198321);
$$;
REVOKE ALL ON FUNCTION tenant_lock_key(uuid) FROM PUBLIC;
GRANT
EXECUTE
ON
FUNCTION
tenant_lock_key
(uuid) TO saas_runtime;
CREATE FUNCTION guard_tenant_control_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
selected uuid;
BEGIN
 IF
TG_TABLE_NAME='tenants' THEN selected:=OLD.id;
  IF
TG_OP='UPDATE' AND NEW.id<>OLD.id THEN RAISE EXCEPTION 'Tenant identity immutable' USING ERRCODE='23514';
END IF;
ELSE selected:=OLD.tenant_id;
  IF
TG_OP='UPDATE' AND NEW.tenant_id<>OLD.tenant_id THEN RAISE EXCEPTION 'Tenant membership reassignment prohibited' USING ERRCODE='23514';
END IF;
END IF;
 -- Application control flows acquire this fence before touching rows. Uncoordinated operator SQL
 -- must fail fast rather than deadlock with an already-fenced transaction waiting on its row.
 IF
NOT pg_try_advisory_xact_lock(tenant_lock_key(selected)) THEN
  RAISE EXCEPTION 'Tenant has active transactions; use the fenced control flow' USING ERRCODE='55P03';
END IF;
 IF
TG_OP='DELETE' THEN RETURN OLD;
ELSE RETURN NEW;
END IF;
END $$;
CREATE TRIGGER tenants_control_fence
    BEFORE UPDATE OR
DELETE
ON tenants FOR EACH ROW
EXECUTE FUNCTION guard_tenant_control_change();
CREATE TRIGGER memberships_control_fence
    BEFORE UPDATE OR
DELETE
ON memberships FOR EACH ROW
EXECUTE FUNCTION guard_tenant_control_change();
CREATE
OR REPLACE FUNCTION reserve_api_request(selected uuid, actor text) RETURNS text
LANGUAGE plpgsql SECURITY INVOKER SET search_path = public, pg_temp AS $$
DECLARE
cap bigint;rate_cap
int;current_period
date:=date_trunc('month',now())::date;
 current_window
timestamptz;previous_window
timestamptz;used
bigint;window_used
int;
BEGIN
 PERFORM
pg_advisory_xact_lock_shared(tenant_lock_key(selected));
 PERFORM
set_config('app.current_tenant',selected::text,true);
SELECT p.api_requests, p.api_rps
INTO cap,rate_cap
FROM tenants t
         JOIN memberships m ON m.tenant_id = t.id
         JOIN plans p ON p.name = t.plan
WHERE t.id = selected
  AND t.state = 'ACTIVE'
  AND m.subject = actor
  AND m.active;
IF
NOT FOUND THEN RETURN 'DENIED';
END IF;
INSERT INTO usage(tenant_id, period)
VALUES (selected, current_period) ON CONFLICT DO NOTHING;
SELECT api, window_at, window_count
INTO used,previous_window,window_used
FROM usage
WHERE tenant_id = selected
  AND period = current_period FOR UPDATE;
IF
used>=cap THEN RETURN 'MONTHLY';
END IF;
 current_window
:=date_trunc('second',clock_timestamp());
 IF
previous_window=current_window AND window_used>=rate_cap THEN RETURN 'RATE';
END IF;
UPDATE usage
SET api=api + 1,
    window_at=current_window,
    window_count=CASE WHEN previous_window = current_window THEN window_count + 1 ELSE 1 END
WHERE tenant_id = selected
  AND period = current_period;
RETURN 'OK';
END $$;
