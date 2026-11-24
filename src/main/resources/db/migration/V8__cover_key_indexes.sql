CREATE INDEX ix_source_item__cover_key
    ON teaplayer_source_item (cover_key)
    WHERE cover_key IS NOT NULL;

CREATE INDEX ix_title_group__cover_key
    ON teaplayer_title_group (cover_key)
    WHERE cover_key IS NOT NULL;

UPDATE teaplayer_service_meta
   SET meta_value = '6'
 WHERE meta_key = 'schema_version';
