-- 移除 V3 中随种子写入的第三方明文凭据（该 key 已进入 Git 历史，必须在源站轮换）。
UPDATE teaplayer_source
   SET spec = jsonb_set(spec, '{headers}', '{}'::jsonb, true)
 WHERE id = 'xifan'
   AND spec ? 'headers';

UPDATE teaplayer_service_meta
   SET meta_value = '8', updated_at = now()
 WHERE meta_key = 'schema_version';
