package app.openai

import app.OpenAIConfig
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException

private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
private const val MAX_LOG_BODY_LENGTH = 2_048
private const val DEFAULT_TEMPERATURE = 0.7

class OpenAIClient(
    private val config: OpenAIConfig,
    private val mapper: ObjectMapper,
    private val client: OkHttpClient
) {
    private val logger = LoggerFactory.getLogger(OpenAIClient::class.java)

    suspend fun complete(messages: List<ChatMessage>): String? {
        if (config.apiKey.isBlank() || config.model.isBlank()) {
            logger.warn("OpenAI configuration missing, skipping completion request")
            return null
        }
        val payload = ChatCompletionRequest(
            model = config.model,
            messages = messages,
            temperature = DEFAULT_TEMPERATURE
        )
        val body = mapper.writeValueAsString(payload).toRequestBody(MEDIA_TYPE_JSON)
        val requestBuilder = Request.Builder()
            .url(OPENAI_URL)
            .post(body)
            .header("Authorization", "Bearer ${config.apiKey}")
        config.organization?.let { requestBuilder.header("OpenAI-Organization", it) }
        config.project?.let { requestBuilder.header("OpenAI-Project", it) }
        val request = requestBuilder.build()
        return try {
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val logBody = truncateForLog(rawBody)
                if (!response.isSuccessful) {
                    logger.warn(
                        "OpenAI request failed: status={} model={} url={} body={}",
                        response.code,
                        config.model,
                        OPENAI_URL,
                        logBody
                    )
                    return null
                }
                if (rawBody.isBlank()) {
                    logger.warn("OpenAI response body empty: model={} url={}", config.model, OPENAI_URL)
                    return null
                }
                val completion = runCatching { mapper.readValue(rawBody, ChatCompletionResponse::class.java) }
                    .onFailure {
                        logger.warn(
                            "OpenAI response parse error: model={} url={} body={}",
                            config.model,
                            OPENAI_URL,
                            logBody,
                            it
                        )
                    }
                    .getOrNull() ?: return null
                completion.choices.firstOrNull()?.message?.content
            }
        } catch (timeout: SocketTimeoutException) {
            logger.warn(
                "OpenAI request timeout: model={} url={} error={}",
                config.model,
                OPENAI_URL,
                timeout.message
            )
            null
        } catch (io: IOException) {
            logger.warn(
                "OpenAI request I/O error: model={} url={} error={}",
                config.model,
                OPENAI_URL,
                io.message
            )
            null
        } catch (ex: Exception) {
            logger.error("OpenAI request failed: model={} url={}", config.model, OPENAI_URL, ex)
            null
        }
    }
}

private fun truncateForLog(content: String, limit: Int = MAX_LOG_BODY_LENGTH): String {
    if (content.length <= limit) {
        return content
    }
    return content.take(limit) + "… [truncated]"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatMessage(
    val role: String,
    val content: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val choices: List<ChatCompletionChoice>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionChoice(
    val index: Int,
    val message: ChatMessage
)
