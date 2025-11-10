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

Copy `.env.example` to `.env` and fill in secrets. On startup the application loads variables from `.env` (if present) and then overrides them with `System.getenv()` values so that container/platform secrets always win.

```bash
cp .env.example .env
```

Key variables:

- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_SECRET_TOKEN`, `TELEGRAM_WEBHOOK_URL`, `TELEGRAM_TRANSPORT`
- `OPENAI_API_KEY`, `OPENAI_MODEL`
- Billing knobs: `FREE_TOTAL_MSG_LIMIT`, `PREMIUM_PRICE_EUR`, `PREMIUM_DURATION_DAYS`, `REMINDER_DAYS_BEFORE`
- Database connection via `DB_DRIVER` and `DB_URL`
- `LOG_RETENTION_DAYS` for Logback rotation

## Local Development

### Wrapper

```bash
./gradlew wrapper --gradle-version 8.7
```

### Local run

```bash
cp .env.example .env   # заполнить секреты
./gradlew clean test installDist
./build/install/ChatBotChefEU/bin/ChatBotChefEU
```

Health endpoint: `GET http://localhost:8080/health` → `OK`.

### Pre-commit hook

```
git update-index --chmod=+x .git/hooks/pre-commit
```

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

### Transport switch
- WEBHOOK (prod): TELEGRAM_TRANSPORT=WEBHOOK
  - Требуется: TELEGRAM_WEBHOOK_URL, TELEGRAM_SECRET_TOKEN, nginx proxy → http://127.0.0.1:8081
- LONG_POLLING (dev/local): TELEGRAM_TRANSPORT=LONG_POLLING
  - Рекомендация: предварительно удалить вебхук:
    curl -sS "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/deleteWebhook"
  - Запуск локально: `TELEGRAM_TRANSPORT=LONG_POLLING ./gradlew run`
  - В этом режиме бот выключает Ktor и держит только цикл long-polling

### Prompts

Mode-specific system prompts live in `resources/prompts/*.prompt.md`. Update those markdown files to tweak the recipe, calorie calculator, ingredient macro, or help personas without recompiling the app.

The `/start` welcome photo ships as a Base64-encoded JPEG in `resources/start_welcome.jpg.b64`. Replace the contents with your own image data or set `START_WELCOME_URL` in the environment to load a remote asset at runtime.

## Testing

The project ships with Kotlin test suites covering i18n placeholder replacement, update deduplication, and webhook smoke behaviour. Run them via `./gradlew test`.
