ALTER TABLE tenants
    ADD COLUMN data_epoch bigint NOT NULL DEFAULT 0;
CREATE FUNCTION bump_tenant_data_epoch() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
selected uuid:=nullif(current_setting('app.current_tenant',true),'')::uuid;
BEGIN
 IF
selected IS NOT NULL THEN
UPDATE tenants
SET data_epoch=data_epoch + 1
WHERE id = selected;
END IF;
RETURN NULL;
END $$;
-- Statement-level invalidation avoids a metadata UPDATE for every row in a large bulk mutation.
CREATE TRIGGER projects_query_epoch
    AFTER INSERT OR
UPDATE OR
DELETE
ON projects FOR EACH STATEMENT
    EXECUTE FUNCTION bump_tenant_data_epoch();
CREATE TRIGGER tasks_query_epoch
    AFTER INSERT OR
UPDATE OR
DELETE
ON tasks FOR EACH STATEMENT
    EXECUTE FUNCTION bump_tenant_data_epoch();
