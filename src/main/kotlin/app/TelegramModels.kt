package app

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Update(
    @JsonProperty("update_id") val updateId: Long,
    val message: Message? = null,
    @JsonProperty("callback_query") val callbackQuery: CallbackQuery? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Message(
    val message_id: Long,
    val from: TelegramUser?,
    val chat: Chat,
    val date: Long,
    val text: String? = null,
    @JsonProperty("reply_to_message") val replyToMessage: Message? = null,
    val document: Document? = null,
    val photo: List<PhotoSize>? = null,
    val entities: List<MessageEntity>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Chat(
    val id: Long,
    val type: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    val id: Long,
    @JsonProperty("is_bot") val isBot: Boolean?,
    val first_name: String? = null,
    val last_name: String? = null,
    val username: String? = null,
    val language_code: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Document(val file_id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PhotoSize(val file_id: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MessageEntity(
    val offset: Int,
    val length: Int,
    val type: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CallbackQuery(
    val id: String,
    val from: TelegramUser,
    val message: Message?,
    val data: String?
)

data class InlineKeyboardMarkup(
    @JsonProperty("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>
)

data class InlineKeyboardButton(
    val text: String,
    @JsonProperty("callback_data") val callbackData: String
)

data class KeyboardButton(val text: String)

data class ReplyKeyboardMarkup(
    val keyboard: List<List<KeyboardButton>>,
    @JsonProperty("resize_keyboard") val resizeKeyboard: Boolean = true,
    @JsonProperty("one_time_keyboard") val oneTimeKeyboard: Boolean = false,
    val selective: Boolean = false
)

data class ReplyKeyboardRemove(
    @JsonProperty("remove_keyboard") val removeKeyboard: Boolean = true,
    val selective: Boolean = false
)

sealed class InputFile {
    data class Url(val value: String) : InputFile()
    data class Bytes(
        val filename: String,
        val bytes: ByteArray,
        val contentType: String = "application/octet-stream"
    ) : InputFile()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T?,
    val description: String? = null
)
