#!/usr/bin/env bash
set -euo pipefail

DOMAIN="aichefbot.org"
ALT_DOMAIN="bot.aichefbot.org"
CERT_DIR="/etc/ssl/chatbotchefeu"
KTOR_UPSTREAM="http://127.0.0.1:8080"
BOT_TOKEN="<<<ВСТАВЬ_ТОКЕН_Бота>>>"
SECRET_TOKEN="<<<ВСТАВЬ_ЛЮБОЙ_СЕКРЕТ_ДЛЯ_ВЕБХУКА>>>"

log() {
    echo "[+] $1"
}

error() {
    echo "[!] $1" >&2
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        error "Command '$1' is required but not installed."
        exit 1
    fi
}

log "Ensuring required commands are available"
require_command apt-get
require_command systemctl
require_command curl

log "Updating apt cache and installing nginx"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y nginx

log "Enabling and starting nginx service"
systemctl enable --now nginx

if command -v ufw >/dev/null 2>&1; then
    if ufw status | grep -q "Status: active"; then
        log "Allowing ports 80 and 443 through UFW"
        ufw allow 80,443/tcp || true
    else
        log "UFW is installed but not active; skipping rule addition"
    fi
else
    log "UFW not installed; skipping firewall configuration"
fi

log "Ensuring certificate directory exists at $CERT_DIR"
mkdir -p "$CERT_DIR"

backup_dir="/var/backups/nginx-$(date +%Y%m%d-%H%M%S)"
files_to_backup=("/etc/nginx/nginx.conf" "/etc/nginx/conf.d/chatbotchefeu.conf")
need_backup=false
for file in "${files_to_backup[@]}"; do
    if [ -f "$file" ]; then
        need_backup=true
        break
    fi
done

if [ "$need_backup" = true ]; then
    log "Creating backup directory at $backup_dir"
    mkdir -p "$backup_dir"
    for file in "${files_to_backup[@]}"; do
        if [ -f "$file" ]; then
            log "Backing up $file"
            cp "$file" "$backup_dir"/
        fi
    done
else
    log "No existing nginx configuration files to back up"
fi

log "Writing /etc/nginx/nginx.conf"
cat <<NGINX_CONF > /etc/nginx/nginx.conf
user  nginx;
worker_processes  auto;

error_log  /var/log/nginx/error.log warn;
pid        /var/run/nginx.pid;

events {
    worker_connections  1024;
}

http {
    log_format  main  '$remote_addr - $remote_user [$time_local] '
                      '"$request" $status $body_bytes_sent '
                      '"$http_referer" "$http_user_agent" '
                      'rt=$request_time ua="$upstream_addr" '
                      'urt=$upstream_response_time';

    access_log  /var/log/nginx/access.log  main;

    sendfile            on;
    tcp_nopush          on;
    tcp_nodelay         on;
    keepalive_timeout   65;
    types_hash_max_size 2048;

    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;

    client_max_body_size 16m;

    include /etc/nginx/conf.d/*.conf;
}
NGINX_CONF

log "Writing /etc/nginx/conf.d/chatbotchefeu.conf"
cat <<NGINX_SITE_CONF > /etc/nginx/conf.d/chatbotchefeu.conf
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} ${ALT_DOMAIN};
    location = /health { return 200 "OK\n"; add_header Content-Type text/plain; }
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${DOMAIN} ${ALT_DOMAIN};

    ssl_certificate     ${CERT_DIR}/fullchain.pem;
    ssl_certificate_key ${CERT_DIR}/privkey.pem;

    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 10m;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    location = /health {
        default_type text/plain;
        return 200 "OK\n";
    }

    location = /telegram/webhook {
        proxy_pass         ${KTOR_UPSTREAM};
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;

        # Передаем секрет Telegram-библиотеке в Ktor
        proxy_set_header   X-Telegram-Bot-Api-Secret-Token ${SECRET_TOKEN};
    }

    location / {
        proxy_pass         ${KTOR_UPSTREAM};
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
NGINX_SITE_CONF

log "Testing nginx configuration"
if nginx -t; then
    log "Reloading nginx"
    systemctl reload nginx
else
    error "nginx configuration test failed"
    nginx -T || true
    exit 1
fi

if [[ -n "${BOT_TOKEN}" && -n "${SECRET_TOKEN}" ]]; then
    log "Registering Telegram webhook"
    curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
      -d "url=https://${DOMAIN}/telegram/webhook" \
      -d "secret_token=${SECRET_TOKEN}"
    echo
    log "Fetching Telegram webhook info"
    curl -sS "https://api.telegram.org/bot${BOT_TOKEN}/getWebhookInfo"
    echo
else
    log "BOT_TOKEN or SECRET_TOKEN is empty; skipping webhook registration"
fi

log "Deployment report"
echo "Status: OK"
echo "Active server names: ${DOMAIN} ${ALT_DOMAIN}"
echo "Configs: /etc/nginx/nginx.conf, /etc/nginx/conf.d/chatbotchefeu.conf"
echo "Logs: /var/log/nginx/access.log, /var/log/nginx/error.log"
