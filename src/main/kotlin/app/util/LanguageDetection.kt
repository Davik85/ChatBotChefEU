package app.util

import app.LanguageSupport
import java.text.Normalizer

private val MARKS_REGEX = Regex("\\p{M}+")
private val SPLIT_REGEX = Regex("""[^\p{L}\p{M}]+""")
private val NON_LETTER_REGEX = Regex("[^\\p{L}]+")
private val WHITESPACE_REGEX = Regex("\\s+")

private fun normalizeToken(raw: String): String {
    if (raw.isBlank()) return ""
    val normalized = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFKD)
    return normalized.replace(MARKS_REGEX, "").replace(NON_LETTER_REGEX, "")
}

private val HELLO_MAP: Map<String, String> = buildMap {
    fun add(token: String, code: String) {
        val normalized = normalizeToken(token)
        if (normalized.isNotEmpty()) {
            put(normalized, code)
        }
    }

    add("hello", "en")
    add("hi", "en")
    add("hey", "en")
    add("hallo", "de")
    add("servus", "de")
    add("moin", "de")
    add("hola", "es")
    add("buenas", "es")
    add("ciao", "it")
    add("salve", "it")
    add("bonjour", "fr")
    add("salut", "fr")
    add("olá", "pt")
    add("ola", "pt")
    add("hoi", "nl")
    add("cześć", "pl")
    add("czesc", "pl")
    add("živjo", "sl")
    add("zivjo", "sl")
    add("szia", "hu")
    add("sziasztok", "hu")
    add("bună", "ro")
    add("buna", "ro")
    add("здравей", "bg")
    add("здравейте", "bg")
    add("γεια", "el")
    add("γειά", "el")
    add("halløj", "da")
    add("halloj", "da")
    add("hallå", "sv")
    add("halla", "sv")
    add("moi", "fi")
    add("hæ", "is")
    add("hae", "is")
    add("tere", "et")
    add("sveiki", "lv")
    add("labas", "lt")
    add("bok", "hr")
    add("здраво", "sr")
    add("привет", "ru")
    add("здравствуйте", "ru")
    add("привіт", "uk")
    add("вітаю", "uk")
    add("ahojte", "sk")
}

private val LANGUAGE_NAME_MAP: Map<String, String> = buildMap {
    LanguageSupport.nameVariants().forEach { (code, variants) ->
        variants.forEach { variant ->
            val normalized = normalizeToken(variant)
            if (normalized.isNotEmpty()) {
                put(normalized, code)
            }
        }
    }
}

fun detectLanguageByGreeting(raw: String): String? {
    val sanitized = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFKD)
    val firstToken = sanitized
        .trim()
        .replace(SPLIT_REGEX, " ")
        .split(WHITESPACE_REGEX)
        .firstOrNull()
        ?: return null
    val normalized = normalizeToken(firstToken)
    return HELLO_MAP[normalized]
}

fun detectLanguageByName(raw: String): String? {
    val sanitized = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFKD)
    val cleaned = sanitized.replace(SPLIT_REGEX, " ").trim()
    if (cleaned.isEmpty()) return null
    val compact = normalizeToken(cleaned)
    LANGUAGE_NAME_MAP[compact]?.let { return it }
    cleaned.split(WHITESPACE_REGEX).forEach { token ->
        val normalized = normalizeToken(token)
        LANGUAGE_NAME_MAP[normalized]?.let { return it }
    }
    return null
}
