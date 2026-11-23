-- 记录 P2 阶段（V6 cover_key 回填）完成后的 schema 版本。
UPDATE teaplayer_service_meta
SET meta_value = '5'
WHERE meta_key = 'schema_version';
