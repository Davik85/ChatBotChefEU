package app.util

private val HELLO_MAP = mapOf(
    "hello" to "en", "hi" to "en", "hey" to "en",
    "hallo" to "de", "servus" to "de", "moin" to "de",
    "hola" to "es", "buenas" to "es",
    "ciao" to "it", "salve" to "it",
    "bonjour" to "fr", "salut" to "fr",
    "привет" to "ru", "здравствуйте" to "ru"
)

private val NORMALIZE_REGEX = Regex("""[^\p{L}\p{M}\s']+""")

fun detectLanguageByGreeting(raw: String): String? {
    val firstToken = raw.lowercase()
        .trim()
        .replace(NORMALIZE_REGEX, " ")
        .split(Regex("\\s+"))
        .firstOrNull()
        ?: return null
    return HELLO_MAP[firstToken]
}
