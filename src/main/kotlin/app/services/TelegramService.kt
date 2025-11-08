package app.services

import app.TelegramConfig
import app.SendMessageRequest
import app.InlineKeyboardMarkup
import app.InlineKeyboardButton
import app.TelegramParseMode
import app.telegram.MarkdownV2Escaper
import app.util.SecretMasker
import com.fasterxml.jackson.databind.ObjectMapper
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
        val params = mutableMapOf<String, Any>(
            "chat_id" to request.chatId.toString(),
            "text" to request.text,
            "disable_web_page_preview" to request.disableWebPagePreview
        )

        val normalizedParseMode = request.parseMode
            ?.trim()
            ?.takeIf { it.equals("HTML", ignoreCase = true) || it.equals("MarkdownV2", ignoreCase = true) }
            ?.let { if (it.equals("HTML", ignoreCase = true)) "HTML" else "MarkdownV2" }

        if (normalizedParseMode != null) {
            params["parse_mode"] = normalizedParseMode
        }

        if (!request.entities.isNullOrEmpty()) {
            params.remove("parse_mode")
            params["entities"] = request.entities
        }

        request.replyMarkup?.let { params["reply_markup"] = it }

        executePost(url, params)
    }

    suspend fun safeSendMessage(chatId: Long, text: String, markup: InlineKeyboardMarkup? = null) {
        val (finalText, parseMode) = when (config.parseMode) {
            TelegramParseMode.MARKDOWNV2 -> MarkdownV2Escaper.escape(text) to "MarkdownV2"
            TelegramParseMode.HTML -> sanitizeHtml(text)
            else -> text to null
        }
        val sanitizedMarkup = sanitizeMarkup(markup)
        val request = SendMessageRequest(
            chatId = chatId,
            text = finalText,
            parseMode = parseMode,
            replyMarkup = sanitizedMarkup
        )
        sendMessage(request)
    }

    suspend fun notifyAdmins(text: String) {
        config.adminIds.forEach { adminId ->
            try {
                safeSendMessage(adminId, text)
            } catch (ex: Exception) {
                logger.warn("Failed to notify admin {}: {}", adminId, ex.message)
            }
        }
    }

    suspend fun answerCallback(callbackId: String, text: String? = null) {
        val url = "$baseUrl/bot${config.botToken}/answerCallbackQuery"
        val payload = mutableMapOf<String, Any>(
            "callback_query_id" to callbackId
        )
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
            val responseBody = response.body?.string().orEmpty()
            val safeUrl = url.replace(config.botToken, SecretMasker.mask(config.botToken))
            if (!response.isSuccessful) {
                logTelegramApiError(response.code, safeUrl, responseBody)
                return
            }
            val parsed = runCatching { mapper.readTree(responseBody) }.getOrNull()
            val ok = parsed?.get("ok")?.asBoolean() ?: true
            if (!ok) {
                val errorCode = parsed?.get("error_code")?.asInt()
                val description = parsed?.get("description")?.asText()
                val parameters = parsed?.get("parameters")
                logger.warn(
                    "Telegram API returned ok=false: status={} url={} error_code={} description={} parameters={} body={}",
                    response.code,
                    safeUrl,
                    errorCode,
                    description,
                    parameters,
                    responseBody
                )
            }
        }
    }

    private fun sanitizeHtml(text: String): Pair<String, String?> {
        val unsafe = text.contains('<') || text.contains('>') || text.contains("&")
        return if (unsafe) {
            text to null
        } else {
            text to "HTML"
        }
    }

    private fun sanitizeMarkup(markup: InlineKeyboardMarkup?): InlineKeyboardMarkup? {
        if (markup == null) return null
        val normalizedRows = mutableListOf<List<InlineKeyboardButton>>()
        markup.inlineKeyboard.forEach { row ->
            val sanitizedButtons = row.filter { button ->
                button.text.isNotBlank() && button.callbackData.isNotBlank()
            }
            if (sanitizedButtons.isNotEmpty()) {
                normalizedRows += sanitizedButtons
            }
        }
        return if (normalizedRows.isEmpty()) null else InlineKeyboardMarkup(normalizedRows)
    }

    private fun logTelegramApiError(statusCode: Int, url: String, responseBody: String) {
        val parsed = runCatching { mapper.readTree(responseBody) }.getOrNull()
        if (parsed == null) {
            logger.warn(
                "Telegram API error: status={} url={} body={}",
                statusCode,
                url,
                responseBody
            )
            return
        }
        val errorCode = parsed.get("error_code")?.asInt()
        val description = parsed.get("description")?.asText()
        val parameters = parsed.get("parameters")
        logger.warn(
            "Telegram API error: status={} url={} error_code={} description={} parameters={} body={}",
            statusCode,
            url,
            errorCode,
            description,
            parameters,
            responseBody
        )
    }
}
