package app

object LanguageSupport {
    private data class LanguageInfo(
        val code: String,
        val nativeName: String,
        val inlineLabel: String? = null,
        val synonyms: List<String> = emptyList(),
    )

    private val languages = listOf(
        LanguageInfo(
            code = "en",
            nativeName = "English",
            inlineLabel = "\uD83C\uDDEC\uD83C\uDDE7 EN",
            synonyms = listOf("english", "en", "eng")
        ),
        LanguageInfo(
            code = "de",
            nativeName = "Deutsch",
            inlineLabel = "\uD83C\uDDE9\uD83C\uDDEA DE",
            synonyms = listOf("deutsch", "german", "de", "ger")
        ),
        LanguageInfo(
            code = "it",
            nativeName = "Italiano",
            inlineLabel = "\uD83C\uDDEE\uD83C\uDDF9 IT",
            synonyms = listOf("italiano", "italian", "it", "ita")
        ),
        LanguageInfo(
            code = "es",
            nativeName = "Español",
            inlineLabel = "\uD83C\uDDEA\uD83C\uDDF8 ES",
            synonyms = listOf("español", "espanol", "spanish", "es", "spa")
        ),
        LanguageInfo(
            code = "fr",
            nativeName = "Français",
            inlineLabel = "\uD83C\uDDEB\uD83C\uDDF7 FR",
            synonyms = listOf("français", "francais", "french", "fr", "fra")
        ),
        LanguageInfo(
            "pt",
            "Português",
            synonyms = listOf(
                "portuguese",
                "portugues",
                "português",
                "português brasileiro",
                "portugues brasileiro",
                "pt"
            )
        ),
        LanguageInfo("nl", "Nederlands", synonyms = listOf("dutch", "nederlands", "hollands", "vlaams", "nl")),
        LanguageInfo("pl", "Polski", synonyms = listOf("polski", "polish", "polska", "pl")),
        LanguageInfo("cs", "Čeština", synonyms = listOf("čeština", "cestina", "česky", "cesky", "czech", "cs")),
        LanguageInfo(
            "sk",
            "Slovenčina",
            synonyms = listOf("slovenčina", "slovencina", "slovenský", "slovensky", "slovak", "sk")
        ),
        LanguageInfo(
            "sl",
            "Slovenščina",
            synonyms = listOf("slovenščina", "slovenscina", "slovenski", "slovene", "slovenian", "sl", "slo")
        ),
        LanguageInfo("hu", "Magyar", synonyms = listOf("magyar", "hungarian", "hu")),
        LanguageInfo(
            "ro",
            "Română",
            synonyms = listOf("română", "romana", "românesc", "romanes", "romanian", "ro")
        ),
        LanguageInfo(
            "bg",
            "Български",
            synonyms = listOf("български", "bulgarski", "bulgarian", "bg")
        ),
        LanguageInfo(
            "el",
            "Ελληνικά",
            synonyms = listOf("ελληνικά", "ellinika", "greek", "hellenic", "el")
        ),
        LanguageInfo("da", "Dansk", synonyms = listOf("dansk", "danske", "danish", "da")),
        LanguageInfo("sv", "Svenska", synonyms = listOf("svenska", "svensk", "swedish", "sv")),
        LanguageInfo("fi", "Suomi", synonyms = listOf("suomi", "suomea", "finnish", "finska", "fi")),
        LanguageInfo(
            "no",
            "Norsk",
            synonyms = listOf("norsk", "norwegian", "nynorsk", "bokmål", "bokmal", "no")
        ),
        LanguageInfo(
            "is",
            "Íslenska",
            synonyms = listOf("íslenska", "islenska", "islensku", "icelandic", "is")
        ),
        LanguageInfo("et", "Eesti", synonyms = listOf("eesti", "estonian", "eesti keel", "et")),
        LanguageInfo(
            "lv",
            "Latviešu",
            synonyms = listOf("latviešu", "latviesu", "latviski", "latvian", "lv")
        ),
        LanguageInfo(
            "lt",
            "Lietuvių",
            synonyms = listOf("lietuvių", "lietuviu", "lietuviskai", "lithuanian", "lt")
        ),
        LanguageInfo(
            "hr",
            "Hrvatski",
            synonyms = listOf("hrvatski", "hrvatski jezik", "croatian", "croatia", "hr")
        ),
        LanguageInfo(
            "sr",
            "Српски",
            synonyms = listOf("српски", "srpski", "srpski jezik", "serbian", "sr")
        ),
        LanguageInfo("ru", "Русский", synonyms = listOf("русский", "russkiy", "russian", "ru")),
        LanguageInfo(
            "uk",
            "Українська",
            synonyms = listOf("українська", "ukrainska", "ukrayinska", "ukrainian", "uk")
        ),
    )

    private val languagesByCode = languages.associateBy { it.code }
    private val primaryLocales = languages.take(5).map { it.code }

    val supportedLocales: Set<String> = languagesByCode.keys

    fun orderedLocales(): List<String> = primaryLocales

    fun allLocales(): List<String> = languages.map { it.code }

    fun inlineLabel(locale: String): String = languagesByCode[locale]?.inlineLabel ?: locale.uppercase()

    fun nativeName(locale: String): String = languagesByCode[locale]?.nativeName ?: locale.uppercase()

    fun supportedLanguageNames(): List<String> = languages.map { it.nativeName }

    fun supportedLanguageList(): String = supportedLanguageNames().joinToString(", ")

    fun nameVariants(): Map<String, List<String>> = languages.associate { info ->
        val variants = buildSet {
            add(info.code)
            add(info.nativeName)
            addAll(info.synonyms)
        }.toList()
        info.code to variants
    }

    fun isSupported(locale: String?): Boolean = locale != null && supportedLocales.contains(locale)
}
