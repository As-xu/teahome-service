UPDATE teaplayer_service_meta
   SET meta_value = '7', updated_at = now()
 WHERE meta_key = 'schema_version';
