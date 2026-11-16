-- A retained grant must retain the period counter against which it was reserved.
ALTER TABLE api_budgets
    ADD CONSTRAINT api_budgets_usage_fk FOREIGN KEY (tenant_id, period) REFERENCES usage (tenant_id, period);
-- Runtime credentials must not modify or enumerate the migration ledger.
REVOKE ALL ON flyway_schema_history FROM saas_runtime;
-- Legacy pending exports did not persist the requester. Recover only from committed audit,
-- never assume that the tenant owner authorized an unproven background operation.
DO
$$ DECLARE
selected uuid;cutoff
timestamptz;
BEGIN
SELECT installed_on::timestamptz
INTO cutoff
FROM flyway_schema_history
WHERE version = '12'
  AND success;
FOR selected IN
SELECT id
FROM tenants LOOP PERFORM set_config('app.current_tenant',selected::text,true);
UPDATE jobs j
SET requested_by=(SELECT a.actor FROM audit a WHERE a.action = 'EXPORT_REQUESTED' AND a.resource = j.id::text
ORDER BY a.at DESC LIMIT 1)
WHERE j.tenant_id=selected
  AND j.kind='EXPORT'
  AND j.created_at
    <cutoff
  AND j.state IN ('PENDING'
    , 'RUNNING')
  AND NOT j.support_export;
END LOOP;
 PERFORM
set_config('app.current_tenant','',true);
END $$;
