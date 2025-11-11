package app.localization

import app.openai.ChatMessage
import app.openai.OpenAIClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.LinkedHashMap
import java.util.Locale

private const val DEFAULT_CACHE_CAPACITY = 512

class OpenAIAutoLocalizationService(
    private val client: OpenAIClient,
    private val cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
) : AutoLocalizationService {

    private val logger = LoggerFactory.getLogger(OpenAIAutoLocalizationService::class.java)

    private val cache = object : LinkedHashMap<CacheKey, String>(cacheCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>?): Boolean {
            return size > cacheCapacity
        }
    }

    override fun translate(targetLanguage: String, sourceText: String): String? {
        val normalizedLang = targetLanguage.lowercase(Locale.getDefault())
        if (normalizedLang == "en") {
            return sourceText
        }
        if (sourceText.isBlank()) {
            return sourceText
        }
        val key = CacheKey(normalizedLang, sourceText)
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val translated = runCatching {
            requestTranslation(normalizedLang, sourceText)
        }.onFailure {
            logger.warn("Auto-localization failed for lang {}: {}", normalizedLang, it.message)
        }.getOrNull()

        if (!translated.isNullOrBlank()) {
            synchronized(cache) { cache[key] = translated }
        }

        return translated
    }

    private fun requestTranslation(targetLanguage: String, sourceText: String): String? {
        val systemPrompt = "You are a professional localization engine. Translate the user's English UI text into $targetLanguage. Pres" +
            "erve any placeholders wrapped in curly braces (e.g., {name}) and return only the translated text."
        val system = ChatMessage(role = "system", content = systemPrompt)
        val user = ChatMessage(role = "user", content = sourceText)
        return runBlocking { client.complete(listOf(system, user)) }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private data class CacheKey(val language: String, val text: String)
}
