ALTER TABLE usage
    ADD COLUMN api_assumed bigint NOT NULL DEFAULT 0 CHECK (api_assumed >= 0);
ALTER TABLE api_budgets
    ADD COLUMN settlement text NOT NULL DEFAULT 'OPEN' CHECK (settlement IN ('OPEN', 'PROVEN', 'ASSUMED'));
ALTER TABLE api_budgets
    ADD COLUMN settled_at timestamptz;
DO
$$ DECLARE
selected uuid;
BEGIN
FOR selected IN
SELECT id
FROM tenants LOOP PERFORM set_config('app.current_tenant',selected::text,true);
UPDATE api_budgets
SET settlement='PROVEN',
    settled_at=created_at
WHERE closed;
END LOOP;
 PERFORM
set_config('app.current_tenant','',true);
END $$;
CREATE INDEX api_budgets_unsettled ON api_budgets (tenant_id, window_at) WHERE NOT closed;
CREATE
OR REPLACE FUNCTION close_api_budget(selected uuid,lease uuid,unused int) RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
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
NOT FOUND OR (b.closed AND b.settlement<>'ASSUMED') THEN RETURN false;
END IF;
 IF
unused<0 OR unused>b.granted-1 THEN RAISE EXCEPTION 'Invalid returned budget' USING ERRCODE='23514';
END IF;
UPDATE usage
SET api=api - unused,
    api_confirmed=api_confirmed + b.granted - unused - 1,
    api_assumed=api_assumed - CASE WHEN b.settlement = 'ASSUMED' THEN b.granted - 1 ELSE 0 END,
    window_count=CASE WHEN window_at = b.window_at THEN window_count - unused ELSE window_count END
WHERE tenant_id = selected
  AND period = b.period;
UPDATE api_budgets
SET closed= true,
    returned=unused,
    settlement='PROVEN',
    settled_at=clock_timestamp()
WHERE tenant_id = selected
  AND id = lease;
IF
b.settlement='ASSUMED' THEN
  INSERT INTO audit(tenant_id,id,actor,action,resource,correlation) VALUES(selected,gen_random_uuid(),'system:quota','QUOTA_LATE_EVIDENCE',lease::text,gen_random_uuid());
END IF;
RETURN true;
END $$;
CREATE FUNCTION reconcile_api_budgets(selected uuid, actor text, maximum int) RETURNS jsonb
    LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE
b api_budgets%ROWTYPE;n
int:=0;credits
bigint:=0;
BEGIN
 PERFORM
pg_advisory_xact_lock_shared(tenant_lock_key(selected));
 PERFORM
set_config('app.current_tenant',selected::text,true);
 -- Local credits expire no later than this DB second's end. The extra minute is a safety
 -- margin, not proof of non-use. Never refund an ambiguous reservation automatically.
FOR b IN
SELECT *
FROM api_budgets
WHERE tenant_id = selected
  AND NOT closed
  AND window_at < clock_timestamp() - interval '61 seconds'
ORDER BY window_at, id LIMIT least(greatest(maximum, 1), 1000) FOR
UPDATE SKIP LOCKED LOOP
UPDATE usage
SET api_assumed=api_assumed + b.granted - 1
WHERE tenant_id = selected
  AND period = b.period;
UPDATE api_budgets
SET closed= true,
    settlement='ASSUMED',
    settled_at=clock_timestamp()
WHERE tenant_id = selected
  AND id = b.id;
n
:=n+1;credits
:=credits+b.granted-1;
END LOOP;
 IF
n>0 THEN INSERT INTO audit(tenant_id,id,actor,action,resource,correlation) VALUES(selected,gen_random_uuid(),actor,'QUOTA_RECONCILED_CONSERVATIVELY',jsonb_build_object('grants',n,'assumed',credits)::text,gen_random_uuid());
END IF;
RETURN jsonb_build_object('settled_grants', n, 'assumed_credits', credits, 'refunded_credits', 0);
END $$;
REVOKE ALL ON FUNCTION reconcile_api_budgets(uuid,text, int) FROM PUBLIC;
GRANT
EXECUTE
ON
FUNCTION
reconcile_api_budgets
(uuid,text,int) TO saas_runtime;
-- Preserve ASSUMED rows as evidence. Only normal, proven rows qualify for short retention.

ALTER TABLE api_budgets
    ADD COLUMN actor text;
CREATE FUNCTION normalize_budget_settlement() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF
NEW.closed THEN NEW.settlement:='PROVEN';NEW.settled_at
:=clock_timestamp();
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER api_budgets_settlement
    BEFORE INSERT
    ON api_budgets
    FOR EACH ROW EXECUTE FUNCTION normalize_budget_settlement();

CREATE
OR REPLACE FUNCTION grant_api_budget(selected uuid,actor text,requested int,lease uuid,previous uuid,unused int) RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path=public,pg_temp AS $$
DECLARE
previous_grant api_budgets%ROWTYPE;fp
text;cap
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
 PERFORM
pg_advisory_xact_lock(hashtextextended(lease::text,43721));
SELECT *
INTO previous_grant
FROM api_budgets
WHERE tenant_id = selected
  AND id = lease;
IF
FOUND THEN
  IF previous_grant.actor IS DISTINCT FROM actor THEN RETURN jsonb_build_object('status','DENIED');
END IF;
  IF
previous_grant.closed OR previous_grant.fingerprint<>fp OR previous_grant.window_at+interval '1 second'<=clock_timestamp() THEN RETURN jsonb_build_object('status','EXPIRED');
END IF;
RETURN jsonb_build_object('status', 'OK', 'fingerprint', fp, 'amount', previous_grant.granted, 'ttl_ms', greatest(0,
                                                                                                                  floor(extract(
                                                                                                                                epoch
                                                                                                                                FROM
                                                                                                                                previous_grant.window_at +
                                                                                                                                interval
                                                                                                                                '1 second' -
                                                                                                                                clock_timestamp()) *
                                                                                                                        1000)::int-2),
                          'replayed', true);
END IF;
 IF
previous IS NOT NULL THEN PERFORM close_api_budget(selected,previous,unused);
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
INSERT INTO api_budgets(tenant_id, id, period, granted, window_at, fingerprint, closed, actor)
VALUES (selected, lease, period_value, amount, current_window, fp, amount = 1, actor);
RETURN jsonb_build_object('status', 'OK', 'fingerprint', fp, 'amount', amount, 'ttl_ms', ttl);
END $$;
