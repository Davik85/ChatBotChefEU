#!/usr/bin/env bash
set -euo pipefail

LOG_DIR=${LOG_DIR:-/var/log/chatbotchefeu}
RETENTION_DAYS=${LOG_RETENTION_DAYS:-14}

find "$LOG_DIR" -type f -name "chatbotchefeu*.log" -mtime +"${RETENTION_DAYS}" -print -delete
