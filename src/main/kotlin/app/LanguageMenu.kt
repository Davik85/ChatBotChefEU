package app

object LanguageSupport {
    private val orderedLocales = listOf("en", "de", "it", "es", "fr")

    private val inlineLabels = mapOf(
        "en" to "\uD83C\uDDEC\uD83C\uDDE7 EN",
        "de" to "\uD83C\uDDE9\uD83C\uDDEA DE",
        "it" to "\uD83C\uDDEE\uD83C\uDDF9 IT",
        "es" to "\uD83C\uDDEA\uD83C\uDDF8 ES",
        "fr" to "\uD83C\uDDEB\uD83C\uDDF7 FR"
    )

    private val nativeNames = mapOf(
        "en" to "English",
        "de" to "Deutsch",
        "it" to "Italiano",
        "es" to "Español",
        "fr" to "Français"
    )

    val supportedLocales: Set<String> = orderedLocales.toSet()

    fun orderedLocales(): List<String> = orderedLocales

    fun inlineLabel(locale: String): String = inlineLabels[locale] ?: locale.uppercase()

    fun nativeName(locale: String): String = nativeNames[locale] ?: locale.uppercase()

    fun isSupported(locale: String?): Boolean = locale != null && supportedLocales.contains(locale)
}
