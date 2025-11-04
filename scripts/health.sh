#!/usr/bin/env bash
set -euo pipefail

URL=${1:-http://127.0.0.1:8080/health}

status=$(curl -sS -o /dev/null -w "%{http_code}" "$URL")
if [[ "$status" == "200" ]];
then
  echo "Service healthy (${status})"
else
  echo "Service unhealthy (${status})" >&2
  exit 1
fi
