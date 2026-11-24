#!/usr/bin/env bash
set -euo pipefail

APP_PW=$(cat /run/secrets/db_app)
MIG_PW=$(cat /run/secrets/db_migrate)
DEV_PW=$(cat /run/secrets/db_dev)

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
  -v app_pw="$APP_PW" -v mig_pw="$MIG_PW" -v dev_pw="$DEV_PW" <<SQL
CREATE ROLE teahome_owner NOLOGIN;
CREATE ROLE teahome_app LOGIN PASSWORD :'app_pw';
CREATE ROLE teahome_migrate LOGIN PASSWORD :'mig_pw';
CREATE ROLE teahome_dev LOGIN PASSWORD :'dev_pw';
CREATE DATABASE teahome_dev OWNER teahome_dev;
ALTER DATABASE teahome OWNER TO teahome_owner;
REVOKE CONNECT ON DATABASE teahome FROM PUBLIC;
GRANT CONNECT ON DATABASE teahome TO teahome_app, teahome_migrate;
GRANT CONNECT ON DATABASE teahome_dev TO teahome_dev;
SQL

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d teahome <<SQL
ALTER SCHEMA public OWNER TO teahome_owner;
GRANT USAGE ON SCHEMA public TO teahome_app;
GRANT USAGE, CREATE ON SCHEMA public TO teahome_migrate;
ALTER DEFAULT PRIVILEGES FOR ROLE teahome_migrate IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO teahome_app;
ALTER DEFAULT PRIVILEGES FOR ROLE teahome_migrate IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO teahome_app;
-- 追加式表：应用角色只读+插入，禁止改删（V11 对既有库做同样收紧）。
REVOKE UPDATE, DELETE ON teaplayer_audit_log FROM teahome_app;
REVOKE UPDATE, DELETE ON teaplayer_config_revision FROM teahome_app;
GRANT SELECT, INSERT ON teaplayer_audit_log TO teahome_app;
GRANT SELECT, INSERT ON teaplayer_config_revision TO teahome_app;
SQL

if [ -f "$PGDATA/pg_hba.conf" ]; then
  sed -i "/^host[[:space:]]\+all[[:space:]]\+all[[:space:]]\+all/d" "$PGDATA/pg_hba.conf"
  cat >> "$PGDATA/pg_hba.conf" <<HBA
host teahome teahome_app 172.16.0.0/12 scram-sha-256
host teahome teahome_app 192.168.5.0/24 scram-sha-256
host teahome teahome_migrate 172.16.0.0/12 scram-sha-256
host teahome teahome_migrate 192.168.5.0/24 scram-sha-256
host teahome_dev teahome_dev 172.16.0.0/12 scram-sha-256
host teahome_dev teahome_dev 192.168.5.0/24 scram-sha-256
local all all trust
HBA
fi
