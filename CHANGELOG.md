# Changelog

## Unreleased

- Added long-polling only startup path when `TELEGRAM_TRANSPORT=LONG_POLLING` (webhook deleted, Ktor disabled, configurable OkHttp timeouts, resilient backoff).
- Updated environment loading to read `.env` first and override with `System.getenv()` values; logs the effective transport once at boot.
- Reworked the main inline menu with auto-removal, per-user mode tracking, and localized introductions for Recipes, Calorie calculator, Ingredient macros, and Help.
- Stored the last rendered menu message id and the active conversation mode in the database for clean menu lifecycle management.
- Moved system prompts into editable markdown files under `resources/prompts/`.
- Added welcome image blob `resources/start_welcome.jpg.b64` and ensured `/start` sends the image before the inline menu.
- Expanded i18n packs with new menu and mode keys and aligned help texts with the new flows.
- Documented transport setup, prompt locations, and environment behaviour in the README.
- Localized full i18n packs for de, fr, it, es, pt, nl, pl, cs, sk, sl, hu, ro, bg, el, da, sv, fi, no, is, et, lv, lt, hr, sr, ru, and uk.
