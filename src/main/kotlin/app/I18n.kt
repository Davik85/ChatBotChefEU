package app

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.text.MessageFormat
import java.util.Locale

private const val DEFAULT_LANGUAGE = "en"
private val SUPPORTED_LANGUAGES = setOf("en", "de", "es", "it", "fr")

class I18n(private val translations: Map<String, Map<String, String>>) {
    fun resolveLanguage(language: String?): String {
        val normalized = language?.lowercase(Locale.getDefault()) ?: DEFAULT_LANGUAGE
        return if (translations.containsKey(normalized)) normalized else DEFAULT_LANGUAGE
    }

    fun translate(language: String?, key: String, variables: Map<String, String> = emptyMap()): String {
        val lang = resolveLanguage(language)
        val value = translations[lang]?.get(key)
            ?: translations[DEFAULT_LANGUAGE]?.get(key)
            ?: key
        return formatPlaceholders(value, variables)
    }

    fun keywords(language: String?, key: String): List<String> {
        val lang = resolveLanguage(language)
        val raw = translations[lang]?.get(key)
            ?: translations[DEFAULT_LANGUAGE]?.get(key)
            ?: return emptyList()
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
        fun load(mapper: ObjectMapper, basePath: String = "i18n"): I18n {
            val translations = mutableMapOf<String, Map<String, String>>()
            for (language in SUPPORTED_LANGUAGES) {
                val path = "$basePath/$language.json"
                val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                    ?: throw IllegalStateException("Missing translation file for $language at $path")
                translations[language] = readTranslation(mapper, stream)
            }
            return I18n(translations)
        }

        private fun readTranslation(mapper: ObjectMapper, stream: InputStream): Map<String, String> {
            stream.use {
                val node: JsonNode = mapper.readTree(it)
                if (!node.isObject) {
                    throw IllegalStateException("Translation file must be an object")
                }
                val result = mutableMapOf<String, String>()
                node.fields().forEachRemaining { (key, value) ->
                    result[key] = if (value.isArray) {
                        value.joinToString(separator = ",") { element -> element.asText() }
                    } else {
                        value.asText()
                    }
                }
                return result
            }
        }
    }
}
