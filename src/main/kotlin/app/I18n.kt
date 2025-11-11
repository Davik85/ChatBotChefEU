package app

import app.localization.AutoLocalizationService
import app.localization.NoopAutoLocalizationService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val BASE_LANGUAGE = "en"
private val ENV_DEFAULT = System.getenv("DEFAULT_LOCALE")
    ?.lowercase(Locale.getDefault())
    ?.takeIf { LanguageSupport.isSupported(it) }
private val DEFAULT_LANGUAGE = ENV_DEFAULT ?: BASE_LANGUAGE
class I18n(
    private val translations: Map<String, String>,
    private val autoLocalization: AutoLocalizationService = NoopAutoLocalizationService,
) {
    private val translationCache = ConcurrentHashMap<TranslationCacheKey, String>()

    fun resolveLanguage(language: String?): String {
        val normalized = language?.lowercase(Locale.getDefault()) ?: DEFAULT_LANGUAGE
        return if (LanguageSupport.isSupported(normalized)) normalized else DEFAULT_LANGUAGE
    }

    fun defaultLanguage(): String = DEFAULT_LANGUAGE

    fun translate(language: String?, key: String, variables: Map<String, String> = emptyMap()): String {
        val template = translations[key] ?: return formatPlaceholders(key, variables)
        val lang = resolveLanguage(language)
        if (lang == BASE_LANGUAGE) {
            return formatPlaceholders(template, variables)
        }
        val cacheKey = TranslationCacheKey(lang, key, variablesHash(variables))
        val localizedTemplate = translationCache.computeIfAbsent(cacheKey) {
            val localized = runCatching {
                autoLocalization.translate(lang, template)
            }.getOrNull()
            localized?.takeIf { it.isNotBlank() } ?: template
        }
        return formatPlaceholders(localizedTemplate, variables)
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

    private fun variablesHash(variables: Map<String, String>): Int {
        if (variables.isEmpty()) return 0
        return variables.entries
            .sortedBy { it.key }
            .fold(1) { acc, (key, value) ->
                31 * acc + key.hashCode() * 37 + value.hashCode()
            }
    }

    private data class TranslationCacheKey(val language: String, val key: String, val variableHash: Int)

    companion object {
        fun load(
            mapper: ObjectMapper,
            autoLocalization: AutoLocalizationService,
            basePath: String = "i18n",
        ): I18n {
            val path = "$basePath/${BASE_LANGUAGE}.json"
            val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
                ?: throw IllegalStateException("Missing translation file for ${BASE_LANGUAGE} at $path")
            val translations = readTranslation(mapper, stream)
            return I18n(translations, autoLocalization)
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
