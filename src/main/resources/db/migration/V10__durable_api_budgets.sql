ALTER TABLE usage
    ADD COLUMN api_confirmed bigint NOT NULL DEFAULT 0;
DO
$$ DECLARE
selected uuid;
BEGIN
FOR selected IN
SELECT id
FROM tenants LOOP PERFORM set_config('app.current_tenant',selected::text,true);
UPDATE usage
SET api_confirmed=api
WHERE tenant_id = selected;
END LOOP;
 PERFORM
set_config('app.current_tenant','',true);
END $$;
CREATE TABLE api_budgets
(
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    id          uuid        NOT NULL,
    period date NOT NULL,
    granted     int         NOT NULL CHECK (granted BETWEEN 1 AND 4),
    window_at   timestamptz NOT NULL,
    fingerprint text        NOT NULL,
    closed      boolean     NOT NULL DEFAULT false,
    returned    int         NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
ALTER TABLE api_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_budgets FORCE ROW LEVEL SECURITY;
CREATE
POLICY tenant_scope ON api_budgets USING(tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON api_budgets TO saas_runtime;
CREATE INDEX api_budgets_retention ON api_budgets (tenant_id, created_at) WHERE closed;
CREATE FUNCTION authorize_api_budget(selected uuid, actor text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE
fp text;
BEGIN
 PERFORM
pg_advisory_xact_lock_shared(tenant_lock_key(selected));
 PERFORM
set_config('app.current_tenant',selected::text,true);
SELECT concat(t.plan, ':', p.api_requests, ':', p.api_rps, ':', date_trunc('month', now())::date)
INTO fp
FROM tenants t
         JOIN memberships m ON m.tenant_id = t.id
         JOIN plans p ON p.name = t.plan
WHERE t.id = selected
  AND t.state = 'ACTIVE'
  AND m.subject = actor
  AND m.active;
IF
NOT FOUND THEN RETURN jsonb_build_object('status','DENIED');
END IF;
RETURN jsonb_build_object('status', 'OK', 'fingerprint', fp);
END $$;
CREATE FUNCTION close_api_budget(selected uuid, lease uuid, unused int) RETURNS boolean
    LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE
b api_budgets%ROWTYPE;
BEGIN
 PERFORM
set_config('app.current_tenant',selected::text,true);
SELECT *
INTO b
FROM api_budgets
WHERE tenant_id = selected
  AND id = lease FOR UPDATE;
IF
NOT FOUND OR b.closed THEN RETURN false;
END IF;
 IF
unused<0 OR unused>b.granted-1 THEN RAISE EXCEPTION 'Invalid returned budget' USING ERRCODE='23514';
END IF;
UPDATE usage
SET api=api - unused,
    api_confirmed=api_confirmed + b.granted - unused - 1,
    window_count=CASE WHEN window_at = b.window_at THEN window_count - unused ELSE window_count END
WHERE tenant_id = selected
  AND period = b.period;
UPDATE api_budgets
SET closed= true,
    returned=unused
WHERE tenant_id = selected
  AND id = lease;
RETURN true;
END $$;
CREATE FUNCTION grant_api_budget(selected uuid, actor text, requested int, lease uuid, previous uuid,
                                 unused int) RETURNS jsonb
    LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE
fp text;cap
bigint;rps
int;period_value
date:=date_trunc('month',now())::date;used
bigint;window_used
int;old_window
timestamptz;current_window
timestamptz;amount
int;ttl
int;
BEGIN
 PERFORM
pg_advisory_xact_lock_shared(tenant_lock_key(selected));
 PERFORM
set_config('app.current_tenant',selected::text,true);
 IF
previous IS NOT NULL THEN PERFORM close_api_budget(selected,previous,unused);
END IF;
SELECT p.api_requests, p.api_rps, concat(t.plan, ':', p.api_requests, ':', p.api_rps, ':', period_value)
INTO cap,rps,fp
FROM tenants t
         JOIN memberships m ON m.tenant_id = t.id
         JOIN plans p ON p.name = t.plan
WHERE t.id = selected
  AND t.state = 'ACTIVE'
  AND m.subject = actor
  AND m.active;
IF
NOT FOUND THEN RETURN jsonb_build_object('status','DENIED');
END IF;
INSERT INTO usage(tenant_id, period)
VALUES (selected, period_value) ON CONFLICT DO NOTHING;
SELECT api, window_count, window_at
INTO used,window_used,old_window
FROM usage
WHERE tenant_id = selected
  AND period = period_value FOR UPDATE;
current_window
:=date_trunc('second',clock_timestamp());
 IF
old_window<>current_window THEN window_used:=0;
END IF;
 IF
used>=cap THEN RETURN jsonb_build_object('status','MONTHLY');
END IF;
 IF
window_used>=rps THEN RETURN jsonb_build_object('status','RATE');
END IF;
 amount
:=least(greatest(requested,1)::bigint,4::bigint,cap-used,(rps-window_used)::bigint)::int;
 ttl
:=greatest(0,floor(extract(epoch FROM current_window+interval '1 second'-clock_timestamp())*1000)::int-2);
UPDATE usage
SET api=api + amount,
    api_confirmed=api_confirmed + 1,
    window_at=current_window,
    window_count=window_used + amount
WHERE tenant_id = selected
  AND period = period_value;
INSERT INTO api_budgets(tenant_id, id, period, granted, window_at, fingerprint, closed)
VALUES (selected, lease, period_value, amount, current_window, fp, amount = 1);
RETURN jsonb_build_object('status', 'OK', 'fingerprint', fp, 'amount', amount, 'ttl_ms', ttl);
END $$;
REVOKE ALL ON FUNCTION authorize_api_budget(uuid,text),close_api_budget(uuid,uuid, int),grant_api_budget(uuid,text, int,uuid,uuid, int) FROM PUBLIC;
GRANT
EXECUTE
ON
FUNCTION
authorize_api_budget
(uuid,text),close_api_budget(uuid,uuid,int),grant_api_budget(uuid,text,int,uuid,uuid,int) TO saas_runtime;

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
    api_confirmed=api_confirmed + 1,
    window_at=current_window,
    window_count=CASE WHEN previous_window = current_window THEN window_count + 1 ELSE 1 END
WHERE tenant_id = selected
  AND period = current_period;
RETURN 'OK';
END $$;
