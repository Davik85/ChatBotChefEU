package app.telegram

import app.InputFile
import app.Message
import app.MessageEntity
import app.TelegramConfig
import app.Update
import app.TelegramResponse
import app.util.SecretMasker
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
private const val MAX_LOG_TEXT_LENGTH = 1000

enum class ParseMode { HTML, MARKDOWN }

data class OutMessage(
    val chatId: Long,
    val text: String,
    val parseMode: ParseMode? = null,
    val entities: List<MessageEntity>? = null,
    val disableWebPagePreview: Boolean? = null,
    val replyMarkup: Any? = null
)

class TelegramClient(
    private val config: TelegramConfig,
    private val mapper: ObjectMapper,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.telegram.org"
) {
    private val logger = LoggerFactory.getLogger(TelegramClient::class.java)

    suspend fun sendMessage(message: OutMessage): Message? {
        val params = buildSendMessageParams(message)
        logOutbound("sendMessage", params)
        return executePostForResult("sendMessage", params, Message::class.java)
    }

    suspend fun sendPhoto(
        chatId: Long,
        photo: InputFile,
        caption: String? = null,
        replyMarkup: Any? = null
    ): Message? {
        when (photo) {
            is InputFile.Url -> {
                val payload = mutableMapOf<String, Any>(
                    "chat_id" to chatId,
                    "photo" to photo.value
                )
                if (!caption.isNullOrBlank()) {
                    payload["caption"] = caption
                }
                if (replyMarkup != null) {
                    payload["reply_markup"] = replyMarkup
                }
                logOutbound("sendPhoto", payload)
                return executePostForResult("sendPhoto", payload, Message::class.java)
            }
            is InputFile.Bytes -> {
                val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                bodyBuilder.addFormDataPart("chat_id", chatId.toString())
                val mediaType = photo.contentType.toMediaType()
                bodyBuilder.addFormDataPart(
                    "photo",
                    photo.filename,
                    photo.bytes.toRequestBody(mediaType)
                )
                if (!caption.isNullOrBlank()) {
                    bodyBuilder.addFormDataPart("caption", caption)
                }
                if (replyMarkup != null) {
                    val markupJson = mapper.writeValueAsString(replyMarkup)
                    bodyBuilder.addFormDataPart("reply_markup", markupJson)
                }
                logOutbound(
                    "sendPhoto",
                    mapOf(
                        "chat_id" to chatId,
                        "photo" to "bytes(${photo.filename},${photo.bytes.size})",
                        "caption" to (caption ?: ""),
                        "reply_markup" to if (replyMarkup != null) "present" else "null"
                    )
                )
                return executeMultipartForResult("sendPhoto", bodyBuilder.build(), Message::class.java)
            }
        }
        return null
    }

    suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        parseMode: ParseMode? = null,
        replyMarkup: Any? = null
    ) {
        val payload = mutableMapOf<String, Any>(
            "chat_id" to chatId,
            "message_id" to messageId,
            "text" to text
        )
        when (parseMode) {
            ParseMode.HTML -> payload["parse_mode"] = "HTML"
            ParseMode.MARKDOWN -> payload["parse_mode"] = "Markdown"
            null -> {}
        }
        if (replyMarkup != null) {
            payload["reply_markup"] = replyMarkup
        }
        logOutbound("editMessageText", payload)
        executePost("editMessageText", payload)
    }

    suspend fun editMessageReplyMarkup(chatId: Long, messageId: Long, replyMarkup: Any) {
        val payload = mutableMapOf<String, Any>(
            "chat_id" to chatId,
            "message_id" to messageId,
            "reply_markup" to replyMarkup
        )
        logOutbound("editMessageReplyMarkup", payload)
        executePost("editMessageReplyMarkup", payload)
    }

    suspend fun deleteMessage(chatId: Long, messageId: Long): TelegramResponse<Boolean>? {
        val payload = mapOf(
            "chat_id" to chatId,
            "message_id" to messageId
        )
        logOutbound("deleteMessage", payload)
        return executePostForTelegramResponse("deleteMessage", payload, Boolean::class.java)
    }

    fun answerCallback(callbackId: String, text: String? = null): TelegramResponse<Boolean>? {
        val payload = mutableMapOf<String, Any>(
            "callback_query_id" to callbackId
        )
        if (!text.isNullOrBlank()) {
            payload["text"] = text
            payload["show_alert"] = false
        }
        return executePostForTelegramResponse("answerCallbackQuery", payload, Boolean::class.java)
    }

    fun deleteWebhook(dropPendingUpdates: Boolean) {
        val payload = mapOf("drop_pending_updates" to dropPendingUpdates)
        executePost("deleteWebhook", payload)
    }

    fun getUpdates(offset: Long?, timeoutSec: Int): List<Update> {
        val base = buildUrl("getUpdates").toHttpUrlOrNull() ?: error("Invalid Telegram URL")
        val urlBuilder = base.newBuilder()
        if (offset != null) {
            urlBuilder.addQueryParameter("offset", offset.toString())
        }
        urlBuilder.addQueryParameter("timeout", timeoutSec.toString())
        val url = urlBuilder.build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val pollClient = client.newBuilder()
            .readTimeout(timeoutSec + 10L, TimeUnit.SECONDS)
            .callTimeout(timeoutSec + 20L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        pollClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val safeUrl = maskUrl(url.toString())
            if (!response.isSuccessful) {
                logTelegramApiError(response.code, safeUrl, body)
                return emptyList()
            }
            if (body.isBlank()) {
                return emptyList()
            }
            val node = runCatching { mapper.readTree(body) }.getOrNull() ?: return emptyList()
            val ok = node.get("ok")?.asBoolean() ?: false
            if (!ok) {
                val errorCode = node.get("error_code")?.asInt()
                val description = node.get("description")?.asText()
                logger.warn(
                    "Telegram API returned ok=false: method=getUpdates status={} url={} error_code={} description={} body={}",
                    response.code,
                    safeUrl,
                    errorCode,
                    description,
                    body
                )
                return emptyList()
            }
            val resultNode = node.get("result") ?: return emptyList()
            if (!resultNode.isArray) return emptyList()
            return resultNode.mapNotNull { mapper.treeToValue(it, Update::class.java) }
        }
    }

    private fun executePost(method: String, payload: Any) {
        val url = buildUrl(method)
        val body = mapper.writeValueAsString(payload).toRequestBody(MEDIA_TYPE_JSON)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val safeUrl = maskUrl(url)
            if (!response.isSuccessful) {
                logTelegramApiError(response.code, safeUrl, responseBody)
                return
            }
            if (responseBody.isBlank()) return
            val parsed = runCatching { mapper.readTree(responseBody) }.getOrNull()
            if (parsed != null && parsed.get("ok")?.asBoolean() == false) {
                val errorCode = parsed.get("error_code")?.asInt()
                val description = parsed.get("description")?.asText()
                logger.warn(
                    "Telegram API returned ok=false: method={} status={} url={} error_code={} description={} body={}",
                    method,
                    response.code,
                    safeUrl,
                    errorCode,
                    description,
                    responseBody
                )
            }
        }
    }

    private fun <T> executePostForResult(method: String, payload: Any, clazz: Class<T>): T? {
        val response = executePostForTelegramResponse(method, payload, clazz) ?: return null
        if (response.ok) {
            return response.result
        }
        val safeUrl = maskUrl(buildUrl(method))
        logger.warn(
            "Telegram API returned ok=false: method={} url={} description={} result={}",
            method,
            safeUrl,
            response.description,
            response.result
        )
        return null
    }

    private fun <T> executeMultipartForResult(method: String, body: MultipartBody, clazz: Class<T>): T? {
        val response = executeMultipartForTelegramResponse(method, body, clazz) ?: return null
        if (response.ok) {
            return response.result
        }
        val safeUrl = maskUrl(buildUrl(method))
        logger.warn(
            "Telegram API returned ok=false: method={} url={} description={} result={}",
            method,
            safeUrl,
            response.description,
            response.result
        )
        return null
    }

    private fun <T> executePostForTelegramResponse(method: String, payload: Any, clazz: Class<T>): TelegramResponse<T>? {
        val url = buildUrl(method)
        val body = mapper.writeValueAsString(payload).toRequestBody(MEDIA_TYPE_JSON)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val safeUrl = maskUrl(url)
            if (!response.isSuccessful) {
                logTelegramApiError(response.code, safeUrl, responseBody)
                return null
            }
            if (responseBody.isBlank()) return null
            val type = mapper.typeFactory.constructParametricType(TelegramResponse::class.java, clazz)
            return try {
                @Suppress("UNCHECKED_CAST")
                mapper.readValue(responseBody, type) as TelegramResponse<T>
            } catch (ex: Exception) {
                logger.warn(
                    "Telegram API response parse error: method={} url={} body={}",
                    method,
                    safeUrl,
                    responseBody,
                    ex
                )
                null
            }
        }
    }

    private fun <T> executeMultipartForTelegramResponse(method: String, body: MultipartBody, clazz: Class<T>): TelegramResponse<T>? {
        val url = buildUrl(method)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val safeUrl = maskUrl(url)
            if (!response.isSuccessful) {
                logTelegramApiError(response.code, safeUrl, responseBody)
                return null
            }
            if (responseBody.isBlank()) return null
            val type = mapper.typeFactory.constructParametricType(TelegramResponse::class.java, clazz)
            return try {
                @Suppress("UNCHECKED_CAST")
                mapper.readValue(responseBody, type) as TelegramResponse<T>
            } catch (ex: Exception) {
                logger.warn(
                    "Telegram API response parse error: method={} url={} body={}",
                    method,
                    safeUrl,
                    responseBody,
                    ex
                )
                null
            }
        }
    }

    private fun buildUrl(method: String): String = "$baseUrl/bot${config.botToken}/$method"

    private fun maskUrl(url: String): String = url.replace(config.botToken, SecretMasker.mask(config.botToken))

    private fun logOutbound(method: String, params: Map<String, Any>) {
        val sanitized = sanitizePayload(params)
        val payloadJson = runCatching { mapper.writeValueAsString(sanitized) }.getOrElse { sanitized.toString() }
        logger.info("Telegram outbound method={} payload={}", method, payloadJson)
    }

    private fun sanitizePayload(params: Map<String, Any>): Map<String, Any> {
        val sanitized = LinkedHashMap<String, Any>()
        params.forEach { (key, value) ->
            if (key == "text" && value is String && value.length > MAX_LOG_TEXT_LENGTH) {
                sanitized[key] = value.take(MAX_LOG_TEXT_LENGTH) + "… [truncated]"
            } else {
                sanitized[key] = value
            }
        }
        return sanitized
    }

    private fun logTelegramApiError(statusCode: Int, url: String, responseBody: String) {
        if (responseBody.isBlank()) {
            logger.warn("Telegram API error: status={} url={} body=<empty>", statusCode, url)
            return
        }
        val node = runCatching { mapper.readTree(responseBody) }.getOrNull()
        if (node == null) {
            logger.warn("Telegram API error: status={} url={} body={}", statusCode, url, responseBody)
            return
        }
        val errorCode = node.get("error_code")?.asInt()
        val description = node.get("description")?.asText()
        logger.warn(
            "Telegram API error: status={} url={} error_code={} description={} body={}",
            statusCode,
            url,
            errorCode,
            description,
            responseBody
        )
    }
}

internal fun buildSendMessageParams(m: OutMessage): Map<String, Any> {
    val params = mutableMapOf<String, Any>(
        "chat_id" to m.chatId,
        "text" to m.text
    )

    if (m.entities.isNullOrEmpty()) {
        when (m.parseMode) {
            ParseMode.HTML -> params["parse_mode"] = "HTML"
            ParseMode.MARKDOWN -> params["parse_mode"] = "Markdown"
            null -> {
                // no-op
            }
        }
    } else {
        params["entities"] = m.entities
    }

    m.disableWebPagePreview?.let { params["disable_web_page_preview"] = it }
    m.replyMarkup?.let { params["reply_markup"] = it }

    return params
}
