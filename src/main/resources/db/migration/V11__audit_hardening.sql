-- P3.2：审计/配置历史不可变，应用角色不得 UPDATE/DELETE。
REVOKE UPDATE, DELETE ON teaplayer_audit_log FROM teahome_app;
REVOKE UPDATE, DELETE ON teaplayer_config_revision FROM teahome_app;
GRANT SELECT, INSERT ON teaplayer_audit_log TO teahome_app;
GRANT SELECT, INSERT ON teaplayer_config_revision TO teahome_app;

CREATE OR REPLACE FUNCTION teaplayer_audit_log_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_user IN ('teahome_migrate', 'postgres') THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'teaplayer_audit_log is append-only';
END;
$$;

CREATE OR REPLACE FUNCTION teaplayer_config_revision_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'teaplayer_config_revision is append-only';
END;
$$;

DROP TRIGGER IF EXISTS trg_config_revision__immutable ON teaplayer_config_revision;
CREATE TRIGGER trg_config_revision__immutable
    BEFORE UPDATE OR DELETE ON teaplayer_config_revision
    FOR EACH ROW EXECUTE FUNCTION teaplayer_config_revision_immutable();

UPDATE teaplayer_service_meta
   SET meta_value = '9', updated_at = now()
 WHERE meta_key = 'schema_version';
