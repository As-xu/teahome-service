-- P4 admin console: sudo mode for sensitive operations and schema version bump.
ALTER TABLE teaplayer_admin_session
    ADD COLUMN sudo_until timestamptz;

CREATE INDEX ix_admin_session__expires
    ON teaplayer_admin_session (expires_at);

UPDATE teaplayer_service_meta
   SET meta_value = '10'
 WHERE meta_key = 'schema_version';
