-- V4 表结构变更后同步对外 schema 版本。
UPDATE teaplayer_service_meta
SET meta_value = '4', updated_at = now()
WHERE meta_key = 'schema_version';
