#!/usr/bin/env bash
# 初始化/重置管理端 owner 密码。用法：
#   sudo ./deploy/teahome-admin-reset.sh
#   TEAHOME_ADMIN_PASSWORD='...' sudo -E ./deploy/teahome-admin-reset.sh
set -euo pipefail

APP_DIR="/opt/teahome/app"
ENV_FILE="/opt/teahome/env/.env"
cd "$APP_DIR"

if [[ -z "${TEAHOME_ADMIN_PASSWORD:-}" ]]; then
    read -r -s -p "新密码（至少 12 位，不会显示）： " TEAHOME_ADMIN_PASSWORD
    echo
    read -r -s -p "再次输入： " CONFIRM
    echo
    if [[ "$TEAHOME_ADMIN_PASSWORD" != "$CONFIRM" ]]; then
        echo "两次输入不一致" >&2
        exit 1
    fi
fi
if [[ ${#TEAHOME_ADMIN_PASSWORD} -lt 12 ]]; then
    echo "密码至少 12 位" >&2
    exit 1
fi

sudo docker compose --env-file "$ENV_FILE" run --rm \
    -e TEAHOME_ADMIN_PASSWORD="$TEAHOME_ADMIN_PASSWORD" \
    teahome-admin \
    --spring.profiles.active=admin \
    --spring.main.web-application-type=none \
    --reset-admin-password

unset TEAHOME_ADMIN_PASSWORD CONFIRM
