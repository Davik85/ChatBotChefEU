package app.services

import app.TelegramConfig
import app.SendMessageRequest
import com.fasterxml.jackson.databind.ObjectMapper
import app.util.SecretMasker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()

class TelegramService(
    private val config: TelegramConfig,
    private val mapper: ObjectMapper,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.telegram.org"
) {
    private val logger = LoggerFactory.getLogger(TelegramService::class.java)

    suspend fun sendMessage(request: SendMessageRequest) {
        val url = "$baseUrl/bot${config.botToken}/sendMessage"
        executePost(url, request)
    }

    suspend fun answerCallback(callbackId: String, text: String? = null) {
        val url = "$baseUrl/bot${config.botToken}/answerCallbackQuery"
        val payload = mutableMapOf("callback_query_id" to callbackId)
        if (!text.isNullOrBlank()) {
            payload["text"] = text
            payload["show_alert"] = false
        }
        executePost(url, payload)
    }

    suspend fun broadcast(adminId: Long, targetIds: List<Long>, message: String, parseMode: String?) {
        logger.info("Admin {} triggered broadcast to {} users", adminId, targetIds.size)
        targetIds.forEach { chatId ->
            val request = SendMessageRequest(chatId = chatId, text = message, parseMode = parseMode)
            runCatching { sendMessage(request) }
                .onFailure { logger.warn("Broadcast to {} failed: {}", chatId, it.message) }
        }
    }

    private fun executePost(url: String, payload: Any) {
        val body = mapper.writeValueAsString(payload).toRequestBody(MEDIA_TYPE_JSON)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val safeUrl = url.replace(config.botToken, SecretMasker.mask(config.botToken))
                logger.warn("Telegram API error {} for {}", response.code, safeUrl)
            }
        }
    }
}
