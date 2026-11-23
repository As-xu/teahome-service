#!/bin/sh
set -eu

read_secret() {
  var="$1"
  file_var="${var}_FILE"
  eval "file=\${$file_var:-}"
  eval "current=\${$var:-}"
  if [ -n "$file" ] && [ -z "$current" ]; then
    value="$(cat "$file")"
    export "$var=$value"
  fi
}

read_secret TEAHOME_DB_PASSWORD
read_secret TEAHOME_BOOTSTRAP_TOKEN

exec java -jar /app/app.jar "$@"
