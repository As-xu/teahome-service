#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ENVF=${TEAHOME_ENV_FILE:-/opt/teahome/env/.env}

sudo docker compose --env-file "$ENVF" config -q
git -c http.proxy= -c https.proxy= pull --ff-only

SHA=$(git rev-parse --short HEAD)
grep -q '^API_TAG=' "$ENVF" || echo 'API_TAG=' >> "$ENVF"
sed -i "s/^API_TAG=.*/API_TAG=$SHA/" "$ENVF"

rollback() {
  local prev
  prev=$(cat /opt/teahome/env/last_good 2>/dev/null || true)
  if [ -n "$prev" ]; then
    echo "rollback to $prev" >&2
    sudo env API_TAG="$prev" docker compose --env-file "$ENVF" up -d --no-build
  fi
  exit 1
}

sudo docker compose --env-file "$ENVF" build teahome-api
sudo docker compose --env-file "$ENVF" --profile migrate run --rm teahome-migrate || exit 1
sudo docker compose --env-file "$ENVF" up -d --wait || rollback
echo "$SHA" > /opt/teahome/env/last_good
