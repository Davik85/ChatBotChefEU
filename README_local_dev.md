# Локальный запуск (long-polling)

1. Создайте файл `.env` в корне проекта и укажите переменные без кавычек. Минимальный набор:
   ```
   TELEGRAM_BOT_TOKEN=123456:ABC
   OPENAI_API_KEY=sk-...
   TELEGRAM_TRANSPORT=LONG_POLLING
   TELEGRAM_OFFSET_FILE=./.run/update_offset.dat
   DB_URL=jdbc:sqlite:./.run/dev.db
   PARSE_MODE=HTML
   ```
   При необходимости добавляйте другие ключи — значения из переменных окружения перекрывают `.env`.
2. Перед запуском убедитесь, что переменные из `.env` доступны рантайму (через Run Configuration в IDE или `source .env` / `Get-Content .env | foreach { Set-Variable }`).
3. Проверьте, что у бота отключён вебхук (`getWebhookInfo` должен возвращать пустой `url`).
4. Запустите приложение с транспортом `LONG_POLLING` и отправьте `/start` в чат, чтобы проверить работу. Файлы `./.run/update_offset.dat` и `./.run/dev.db` будут созданы автоматически.

В продакшене загрузка `.env` не используется — значения берутся из переменных окружения (systemd).
