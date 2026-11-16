-- Larger grants amortize synchronous quota WAL; rate/monthly bounds remain atomic.
ALTER TABLE api_budgets DROP CONSTRAINT api_budgets_granted_check;
ALTER TABLE api_budgets
    ADD CONSTRAINT api_budgets_granted_check CHECK (granted BETWEEN 1 AND 16);
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
:=least(greatest(requested,1)::bigint,16::bigint,cap-used,(rps-window_used)::bigint)::int;
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
