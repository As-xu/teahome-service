-- 受控审计清理：函数由 migrate 拥有，SECURITY DEFINER，app 只能按保留天数调用。
CREATE OR REPLACE FUNCTION teaplayer_purge_audit(p_days integer)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    deleted bigint;
BEGIN
    IF p_days IS NULL OR p_days < 30 OR p_days > 3650 THEN
        RAISE EXCEPTION 'audit retention days out of range: %', p_days;
    END IF;
    DELETE FROM public.teaplayer_audit_log
     WHERE occurred_at < now() - make_interval(days => p_days);
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END;
$$;

REVOKE ALL ON FUNCTION teaplayer_purge_audit(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION teaplayer_purge_audit(integer) TO teahome_app;

UPDATE teaplayer_service_meta SET meta_value = '11' WHERE meta_key = 'schema_version';
