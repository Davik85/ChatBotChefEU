package app

private val LANGUAGE_LABELS = mapOf(
    "en" to "English",
    "de" to "Deutsch",
    "es" to "Español",
    "it" to "Italiano",
    "fr" to "Français"
)

object LanguageMenu {
    private const val COLUMN_COUNT = 2

    fun buildMenu(): InlineKeyboardMarkup {
        val buttons = LANGUAGE_LABELS.entries.map { (code, label) ->
            InlineKeyboardButton(text = label, callbackData = "lang:$code")
        }
        val rows = buttons.chunked(COLUMN_COUNT)
        return InlineKeyboardMarkup(rows)
    }
}
