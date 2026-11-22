#!/usr/bin/env bash
set -euo pipefail
umask 077
cd /opt/teahome
exec 9>/var/lock/teahome-backup.lock
flock -n 9
TS=$(date +%F_%H%M)
TMP="backups/teahome-$TS.dump.tmp"
OUT="backups/teahome-$TS.dump.age"
trap 'rm -f "$TMP"' EXIT

sudo -n docker exec -i teahome-db pg_dump -Fc -Z9 -U postgres teahome > "$TMP"
sudo -n docker exec -i teahome-db pg_restore --list < "$TMP" >/dev/null
age -r "$(cat secrets/backup.pub)" -o "$OUT" "$TMP"
rm -f "$TMP"
trap - EXIT
find backups -name 'teahome-*.dump.age' -mtime +14 -delete
