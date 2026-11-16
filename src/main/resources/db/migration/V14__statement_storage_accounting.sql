-- Transition relations aggregate bulk statements once. Zero-byte changes create no counter WAL.
CREATE FUNCTION account_statement_storage() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
selected uuid:=nullif(current_setting('app.current_tenant',true),'')::uuid;
 expression
text;added
bigint:=0;removed
bigint:=0;delta
bigint;cap
bigint;
BEGIN
 expression
:=CASE TG_TABLE_NAME WHEN 'projects' THEN '64+octet_length(name)' WHEN 'tasks' THEN '64+octet_length(title)' WHEN 'jobs' THEN '128+octet_length(coalesce(result::text,''''))' WHEN 'export_chunks' THEN 'octet_length(data)' ELSE NULL
END;
 IF
expression IS NULL THEN RAISE EXCEPTION 'Unsupported accounting table';
END IF;
 IF
TG_OP<>'DELETE' THEN EXECUTE 'SELECT coalesce(sum('||expression||'),0) FROM incoming' INTO added;
END IF;
 IF
TG_OP<>'INSERT' THEN EXECUTE 'SELECT coalesce(sum('||expression||'),0) FROM outgoing' INTO removed;
END IF;
 delta
:=added-removed;
 IF
delta=0 THEN RETURN NULL;
END IF;
 IF
selected IS NULL THEN RAISE EXCEPTION 'Missing tenant accounting scope' USING ERRCODE='23514';
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
RETURN NULL;
END $$;
DROP TRIGGER projects_storage ON projects;
DROP TRIGGER tasks_storage ON tasks;
DROP TRIGGER jobs_storage ON jobs;
DROP TRIGGER export_chunks_storage ON export_chunks;
DO
$$ DECLARE
name text;
BEGIN
 FOREACH
name IN ARRAY ARRAY['projects','tasks','jobs','export_chunks'] LOOP
  EXECUTE format('CREATE TRIGGER %I AFTER INSERT ON %I REFERENCING NEW TABLE AS incoming FOR EACH STATEMENT EXECUTE FUNCTION account_statement_storage()',name||'_storage_insert',name);
EXECUTE format(
        'CREATE TRIGGER %I AFTER UPDATE ON %I REFERENCING NEW TABLE AS incoming OLD TABLE AS outgoing FOR EACH STATEMENT EXECUTE FUNCTION account_statement_storage()',
        name || '_storage_update', name);
EXECUTE format(
        'CREATE TRIGGER %I AFTER DELETE ON %I REFERENCING OLD TABLE AS outgoing FOR EACH STATEMENT EXECUTE FUNCTION account_statement_storage()',
        name || '_storage_delete', name);
END LOOP;
END $$;

DROP FUNCTION account_storage();
DROP FUNCTION account_export_storage();
