ALTER TABLE plans
    ADD COLUMN api_rps int NOT NULL DEFAULT 100 CHECK (api_rps > 0);
CREATE FUNCTION reserve_api_request(selected uuid, actor text) RETURNS text
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
set_config('app.current_tenant',selected::text,true);
SELECT p.api_requests, p.api_rps
INTO cap,rate_cap
FROM tenants t
         JOIN memberships m ON m.tenant_id = t.id
         JOIN plans p ON p.name = t.plan
WHERE t.id = selected
  AND t.state = 'ACTIVE'
  AND m.subject = actor
  AND m.active
    FOR SHARE OF t
    , m;
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
 -- Read wall time only after acquiring the counter lock; older waiters cannot reset a newer window.
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
REVOKE ALL ON FUNCTION reserve_api_request(uuid,text) FROM PUBLIC;
GRANT
EXECUTE
ON
FUNCTION
reserve_api_request
(uuid,text) TO saas_runtime;
