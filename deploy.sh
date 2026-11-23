#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ENVF=${TEAHOME_ENV_FILE:-/opt/teahome/env/.env}

# 以仓库属主身份拉代码：deploy.sh 通常用 sudo 执行，而 deploy key 在属主 ~/.ssh 下。
if [ "$(id -u)" -eq 0 ] && [ -n "${SUDO_USER:-}" ]; then
  GIT=(sudo -u "$SUDO_USER" git)
else
  GIT=(git)
fi

sudo docker compose --env-file "$ENVF" config -q
"${GIT[@]}" -c http.proxy= -c https.proxy= pull --ff-only

SHA=$("${GIT[@]}" rev-parse --short HEAD)
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
