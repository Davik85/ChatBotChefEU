package app.openai

import app.OpenAIConfig
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

private val MEDIA_TYPE_JSON = "application/json; charset=utf-8".toMediaType()
private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
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
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn("OpenAI error code {}", response.code)
                return null
            }
            val json = response.body?.string().orEmpty()
            if (json.isBlank()) {
                logger.warn("Empty response body from OpenAI")
                return null
            }
            val completion = mapper.readValue(json, ChatCompletionResponse::class.java)
            completion.choices.firstOrNull()?.message?.content
        }
    }
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
