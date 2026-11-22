#!/bin/sh
set -eu
if [ -n "${TEAHOME_DB_PASSWORD_FILE:-}" ] && [ -z "${TEAHOME_DB_PASSWORD:-}" ]; then
  TEAHOME_DB_PASSWORD="$(cat "$TEAHOME_DB_PASSWORD_FILE")"
  export TEAHOME_DB_PASSWORD
fi
exec java -jar /app/app.jar "$@"
