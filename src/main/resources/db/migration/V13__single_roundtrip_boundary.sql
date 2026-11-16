-- VOLATILE PL/pgSQL takes the transaction fence before its fresh metadata query.
-- Never cache the returned membership/state in the application.
CREATE FUNCTION establish_tenant(selected uuid, actor text, exclusive boolean)
    RETURNS TABLE
            (
                subscription  text,
                trial_ends_at timestamptz,
                data_epoch    bigint,
                member_role   text,
                fingerprint   text
            )
    LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path=public,pg_temp AS $$
BEGIN
 PERFORM
set_config('app.current_tenant',selected::text,true);
 IF
exclusive THEN PERFORM pg_advisory_xact_lock(tenant_lock_key(selected));
ELSE PERFORM pg_advisory_xact_lock_shared(tenant_lock_key(selected));
END IF;
RETURN QUERY SELECT t.subscription,t.trial_ends_at,t.data_epoch,m.role,concat(t.plan,':',p.api_requests,':',p.api_rps,':',date_trunc('month',now())::date)
 FROM tenants t JOIN memberships m ON m.tenant_id=t.id JOIN plans p ON p.name=t.plan
 WHERE t.id=selected AND t.state='ACTIVE' AND m.subject=actor AND m.active;
END $$;
REVOKE ALL ON FUNCTION establish_tenant(uuid,text,boolean) FROM PUBLIC;
GRANT
EXECUTE
ON
FUNCTION
establish_tenant
(uuid,text,boolean) TO saas_runtime;
