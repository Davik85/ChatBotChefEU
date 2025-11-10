package app.util

sealed interface LanguageCallbackAction {
    data class SetLocale(val locale: String) : LanguageCallbackAction
    object RequestOther : LanguageCallbackAction
}

private val SET_PREFIX = "lang:set:"

fun parseLanguageCallbackData(data: String?): LanguageCallbackAction? {
    if (data == null) return null
    return when {
        data == "lang:other" -> LanguageCallbackAction.RequestOther
        data.startsWith(SET_PREFIX) -> {
            val locale = data.removePrefix(SET_PREFIX)
            if (locale.isBlank()) null else LanguageCallbackAction.SetLocale(locale)
        }
        else -> null
    }
}
