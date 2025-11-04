# ChatBotChefEU

ChatBotChefEU is a Kotlin/Ktor Telegram bot tailored for the EU market. It delivers multilingual recipe inspiration, calorie and macro breakdowns, and premium upsell flows while complying with GDPR-minded storage practices.

## Tech Stack

- Kotlin 1.9 + JVM 17
- Ktor 2.x (Netty)
- Exposed ORM with HikariCP
- SQLite by default (PostgreSQL ready)
- OkHttp + Jackson
- Logback with compact formatting
- Docker & docker-compose with Nginx TLS proxy
- Systemd units for bare-metal deployments

## Configuration

Copy `.env.example` to `.env` and fill in secrets:

```bash
cp .env.example .env
```

Key variables:

- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_SECRET_TOKEN`, `TELEGRAM_WEBHOOK_URL`
- `OPENAI_API_KEY`, `OPENAI_MODEL`
- Billing knobs: `FREE_TOTAL_MSG_LIMIT`, `PREMIUM_PRICE_EUR`, `PREMIUM_DURATION_DAYS`, `REMINDER_DAYS_BEFORE`
- Database connection via `DB_DRIVER` and `DB_URL`
- `LOG_RETENTION_DAYS` for Logback rotation

## Local Development

```bash
./gradlew clean test installDist
./build/install/ChatBotChefEU/bin/ChatBotChefEU
```

Health endpoint: `GET http://localhost:8080/health` → `OK`.

### Running in Docker

```bash
cd docker
docker compose up --build
```

Mount certificates into `docker/nginx/certs` (`fullchain.pem`, `privkey.pem`). The compose stack exposes HTTP (80 → redirect) and HTTPS (443 → app).

### Systemd Deployment

1. Copy the distribution produced by `./gradlew installDist` to `/opt/chatbotchefeu`.
2. Create `/etc/chatbotchefeu/.env` with the production environment variables.
3. Install service files from `docker/systemd/`:
   ```bash
   sudo cp docker/systemd/chatbotchefeu*.service /etc/systemd/system/
   sudo cp docker/systemd/chatbotchefeu.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now chatbotchefeu.service chatbotchefeu.timer
   ```

The timer triggers `/internal/housekeeping/reminders` daily via the accompanying housekeeping unit.

## Webhook Setup

After the bot is deployed over HTTPS, register the webhook:

```bash
export $(grep -v '^#' .env | xargs)
./scripts/setWebhook.sh
```

This configures Telegram with the webhook URL, secret token, and allowed updates.

`./scripts/health.sh` checks `/health`, while `./scripts/rotate-logs.sh` prunes old log files.

## Internationalisation

The bot greets new users with inline buttons for English, Deutsch, Español, Italiano, and Français. User language preferences are stored in the database and can be changed any time via `/language`. All user-facing strings live in `resources/i18n/*.json` and are loaded at runtime.

## Premium & Reminders

- Free users are limited by `FREE_TOTAL_MSG_LIMIT` messages.
- `/premiumstatus` shows expiry information.
- Admins (`ADMIN_IDS`) can grant premium via `/grantpremium <userId> <days>` and broadcast updates with optional Markdown/HTML rendering.
- The reminder endpoint `/internal/housekeeping/reminders` dispatches renewal nudges `REMINDER_DAYS_BEFORE` days prior to expiry. Trigger it through the provided timer, cron, or any scheduler.

## Long Polling Fallback

Webhook mode is the default. To fall back to long polling:

1. Disable the webhook with `setWebhook` and an empty URL.
2. Replace the Netty `embeddedServer` invocation with a background polling coroutine (not implemented here) that calls `getUpdates`.
3. Reuse `UpdateProcessor` for identical business logic.

## Testing

The project ships with Kotlin test suites covering i18n placeholder replacement, update deduplication, and webhook smoke behaviour. Run them via `./gradlew test`.
