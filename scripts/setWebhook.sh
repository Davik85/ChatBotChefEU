#!/usr/bin/env bash
set -euo pipefail

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is required}"
: "${TELEGRAM_WEBHOOK_URL:?TELEGRAM_WEBHOOK_URL is required}"
: "${TELEGRAM_SECRET_TOKEN:?TELEGRAM_SECRET_TOKEN is required}"

ALLOWED_UPDATES='["message","callback_query"]'

curl -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
    -d "url=${TELEGRAM_WEBHOOK_URL}" \
    -d "secret_token=${TELEGRAM_SECRET_TOKEN}" \
    -d "allowed_updates=${ALLOWED_UPDATES}"
