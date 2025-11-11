package app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.util.Locale

private const val BASE_LANGUAGE = "en"
private val ENV_DEFAULT = System.getenv("DEFAULT_LOCALE")
    ?.lowercase(Locale.getDefault())
    ?.takeIf { LanguageSupport.isSupported(it) }
private val DEFAULT_LANGUAGE = ENV_DEFAULT ?: BASE_LANGUAGE
class I18n(
    private val translations: Map<String, Map<String, String>>,
) {
    fun resolveLanguage(language: String?): String {
        val normalized = language?.lowercase(Locale.getDefault()) ?: DEFAULT_LANGUAGE
        return if (translations.containsKey(normalized)) normalized else DEFAULT_LANGUAGE
    }

    fun defaultLanguage(): String = DEFAULT_LANGUAGE

    fun translate(language: String?, key: String, variables: Map<String, String> = emptyMap()): String {
        val lang = resolveLanguage(language)
        val template = translations[lang]?.get(key)
            ?: translations[BASE_LANGUAGE]?.get(key)
            ?: key
        return formatPlaceholders(template, variables)
    }

    fun keywords(language: String?, key: String): List<String> {
        val raw = translate(language, key)
        if (raw == key) {
            return emptyList()
        }
        return raw.split(",").mapNotNull { it.trim().lowercase(Locale.getDefault()).takeIf { word -> word.isNotEmpty() } }
    }

    private fun formatPlaceholders(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (k, v) ->
            result = result.replace("{$k}", v)
        }
        return result
    }

    companion object {
        fun load(
            mapper: ObjectMapper,
            basePath: String = "i18n",
        ): I18n {
            val translations = mutableMapOf<String, Map<String, String>>()
            LanguageSupport.allLocales().forEach { locale ->
                val path = "$basePath/$locale.json"
                val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                    ?: throw IllegalStateException("Missing translation file for $locale at $path")
                translations[locale] = readTranslation(mapper, stream)
            }
            val baseKeys = translations[BASE_LANGUAGE]?.keys
                ?: throw IllegalStateException("Missing base translations for $BASE_LANGUAGE")
            translations.forEach { (locale, values) ->
                val missing = baseKeys - values.keys
                if (missing.isNotEmpty()) {
                    throw IllegalStateException("Missing translation keys for $locale: ${missing.joinToString(", ")}")
                }
            }
            return I18n(translations)
        }

        private fun readTranslation(mapper: ObjectMapper, stream: InputStream): Map<String, String> {
            stream.use {
                val node: JsonNode = mapper.readTree(it)
                if (!node.isObject) {
                    throw IllegalStateException("Translation file must be an object")
                }
                return buildMap {
                    node.fields().forEachRemaining { (key, value) ->
                        put(key, if (value.isArray) {
                            value.joinToString(separator = ",") { element -> element.asText() }
                        } else {
                            value.asText()
                        })
                    }
                }
            }
        }
    }
}
