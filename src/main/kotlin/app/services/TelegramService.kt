package app.services

import app.I18n
import app.InlineKeyboardButton
import app.InlineKeyboardMarkup
import app.InputFile
import app.LanguageSupport
import app.TelegramConfig
import app.TelegramParseMode
import app.telegram.OutMessage
import app.telegram.ParseMode
import app.telegram.TelegramClient
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

class TelegramService(
    private val config: TelegramConfig,
    private val telegramClient: TelegramClient,
    private val i18n: I18n
) {
    private val logger = LoggerFactory.getLogger(TelegramService::class.java)
    private val welcomeImageUrl = config.welcomeImageUrl?.takeIf { it.isNotBlank() }

    suspend fun safeSendMessage(chatId: Long, text: String, replyMarkup: Any? = null): Long? {
        val outbound = buildOutbound(chatId, text, replyMarkup)
        return try {
            telegramClient.sendMessage(outbound)?.message_id
                ?: run {
                    logger.warn(
                        "Telegram sendMessage returned null message_id for chat={} textPreview={}",
                        chatId,
                        preview(text)
                    )
                    null
                }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            logger.warn("Failed to send message chat={} error={}", chatId, ex.message)
            null
        }
    }

    suspend fun sendWelcomeImage(chatId: Long): Long? {
        val url = welcomeImageUrl
        if (url != null) {
            safeSendPhoto(chatId, InputFile.Url(url))?.let { return it }
        }
        val resourceBytes = loadWelcomeResource()
        if (resourceBytes != null) {
            return safeSendPhoto(
                chatId,
                InputFile.Bytes(filename = "welcome.jpg", bytes = resourceBytes, contentType = "image/jpeg")
            )
        } else {
            logger.warn("Welcome image resource not found")
        }
        return null
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
        try {
            val response = telegramClient.answerCallback(callbackId, text)
            if (response == null) {
                return
            }
            if (!response.ok) {
                val description = response.description.orEmpty()
                if (description.contains("query is too old", ignoreCase = true)) {
                    logger.info("Callback {} ignored: query is too old", callbackId)
                } else {
                    logger.warn(
                        "Failed to answer callback {}: description={} result={}",
                        callbackId,
                        description,
                        response.result
                    )
                }
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            logger.warn("Failed to answer callback {}: {}", callbackId, ex.message)
        }
    }

    fun languageMenu(language: String): InlineKeyboardMarkup = InlineKeyboardMarkup(
        listOf(
            listOf(
                btn(LanguageSupport.inlineLabel("en"), "lang:set:en"),
                btn(LanguageSupport.inlineLabel("de"), "lang:set:de"),
                btn(LanguageSupport.inlineLabel("it"), "lang:set:it")
            ),
            listOf(
                btn(LanguageSupport.inlineLabel("es"), "lang:set:es"),
                btn(LanguageSupport.inlineLabel("fr"), "lang:set:fr"),
                btn(i18n.translate(language, "lang.other.button"), "lang:other")
            )
        )
    )

    fun mainMenuKeyboard(language: String): InlineKeyboardMarkup = InlineKeyboardMarkup(
        listOf(
            listOf(btn(i18n.translate(language, "menu.main.btn.recipes"), "mode:recipes")),
            listOf(btn(i18n.translate(language, "menu.main.btn.calorie"), "mode:calorie")),
            listOf(btn(i18n.translate(language, "menu.main.btn.ingredient"), "mode:ingredient")),
            listOf(btn(i18n.translate(language, "menu.main.btn.help"), "mode:help"))
        )
    )

    fun adminMenu(language: String): InlineKeyboardMarkup = InlineKeyboardMarkup(
        listOf(
            listOf(btn(i18n.translate(language, "admin.menu.stats"), "admin:stats")),
            listOf(btn(i18n.translate(language, "admin.menu.broadcast"), "admin:broadcast")),
            listOf(btn(i18n.translate(language, "admin.menu.user_status"), "admin:user_status")),
            listOf(btn(i18n.translate(language, "admin.menu.grant_premium"), "admin:grant_premium")),
            listOf(btn(i18n.translate(language, "admin.menu.lang_stats"), "admin:lang_stats"))
        )
    )

    fun adminBroadcastTypeKeyboard(language: String): InlineKeyboardMarkup = InlineKeyboardMarkup(
        listOf(
            listOf(
                btn(i18n.translate(language, "admin.broadcast.type.text"), "admin:broadcast_type:text"),
                btn(i18n.translate(language, "admin.broadcast.type.photo"), "admin:broadcast_type:photo"),
                btn(i18n.translate(language, "admin.broadcast.type.video"), "admin:broadcast_type:video")
            ),
            listOf(btn(i18n.translate(language, "admin.common.cancel_button"), "admin:cancel"))
        )
    )

    fun adminBroadcastPreviewKeyboard(language: String): InlineKeyboardMarkup = InlineKeyboardMarkup(
        listOf(
            listOf(btn(i18n.translate(language, "admin.broadcast.send"), "admin:broadcast_send")),
            listOf(btn(i18n.translate(language, "admin.common.cancel_button"), "admin:cancel"))
        )
    )

    suspend fun removeInlineKeyboard(chatId: Long, messageId: Long) {
        val emptyMarkup = InlineKeyboardMarkup(emptyList())
        runCatching { telegramClient.editMessageReplyMarkup(chatId, messageId, emptyMarkup) }
            .onFailure { logger.warn("Failed to remove inline keyboard for chat={} message={}: {}", chatId, messageId, it.message) }
    }

    suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean {
        return try {
            val response = telegramClient.deleteMessage(chatId, messageId)
            if (response == null) {
                return false
            }
            if (!response.ok) {
                val description = response.description.orEmpty()
                if (description.contains("message to delete not found", ignoreCase = true)) {
                    logger.debug("Message already deleted chat={} messageId={}", chatId, messageId)
                    return false
                }
                logger.warn(
                    "Failed to delete message chat={} messageId={} description={} result={}",
                    chatId,
                    messageId,
                    description,
                    response.result
                )
                return false
            }
            response.result == true
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            logger.warn("Failed to delete message chat={} messageId={} error={}", chatId, messageId, ex.message)
            false
        }
    }

    private fun buildOutbound(chatId: Long, text: String, replyMarkup: Any? = null): OutMessage {
        val (finalText, parseMode) = when (config.parseMode) {
            TelegramParseMode.MARKDOWN -> text to ParseMode.MARKDOWN
            TelegramParseMode.HTML -> sanitizeHtml(text)
            else -> text to null
        }
        val sanitizedMarkup = when (replyMarkup) {
            is InlineKeyboardMarkup -> sanitizeMarkup(replyMarkup)
            else -> replyMarkup
        }
        return OutMessage(
            chatId = chatId,
            text = finalText,
            parseMode = parseMode,
            disableWebPagePreview = true,
            replyMarkup = sanitizedMarkup
        )
    }

    private fun sanitizeHtml(text: String): Pair<String, ParseMode?> {
        val unsafe = text.contains('<') || text.contains('>') || text.contains("&")
        return if (unsafe) {
            text to null
        } else {
            text to ParseMode.HTML
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

    private fun btn(text: String, data: String) = InlineKeyboardButton(text = text, callbackData = data)

    suspend fun safeSendPhoto(
        chatId: Long,
        photo: InputFile,
        caption: String? = null,
        replyMarkup: Any? = null
    ): Long? {
        val (finalCaption, parseMode) = prepareCaption(caption)
        return try {
            val message = telegramClient.sendPhoto(chatId, photo, finalCaption, parseMode, replyMarkup)
            if (message == null) {
                logger.warn("Telegram sendPhoto returned null for chat={} source={}", chatId, describePhoto(photo))
                return null
            }
            message.message_id
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            logger.warn("Failed to send photo chat={} source={} error={}", chatId, describePhoto(photo), ex.message)
            null
        }
    }

    suspend fun safeSendVideo(
        chatId: Long,
        video: InputFile,
        caption: String? = null,
        replyMarkup: Any? = null
    ): Long? {
        val (finalCaption, parseMode) = prepareCaption(caption)
        return try {
            val message = telegramClient.sendVideo(chatId, video, finalCaption, parseMode, replyMarkup)
            if (message == null) {
                logger.warn("Telegram sendVideo returned null for chat={} source={}", chatId, describeVideo(video))
                return null
            }
            message.message_id
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            logger.warn("Failed to send video chat={} source={} error={}", chatId, describeVideo(video), ex.message)
            null
        }
    }

    suspend fun sendBroadcastPayload(chatId: Long, payload: BroadcastPayload): Boolean {
        return when (payload) {
            is BroadcastPayload.Text -> safeSendMessage(chatId, payload.text) != null
            is BroadcastPayload.Photo -> safeSendPhoto(chatId, InputFile.Existing(payload.fileId), payload.caption) != null
            is BroadcastPayload.Video -> safeSendVideo(chatId, InputFile.Existing(payload.fileId), payload.caption) != null
        }
    }

    private fun prepareCaption(caption: String?): Pair<String?, ParseMode?> {
        val value = caption?.takeIf { it.isNotBlank() } ?: return null to null
        return when (config.parseMode) {
            TelegramParseMode.MARKDOWN -> value to ParseMode.MARKDOWN
            TelegramParseMode.HTML -> sanitizeHtml(value)
            else -> value to null
        }
    }

    private fun describePhoto(photo: InputFile): String = when (photo) {
        is InputFile.Url -> "url"
        is InputFile.Bytes -> "bytes(${photo.filename},${photo.bytes.size})"
        is InputFile.Existing -> "existing"
    }

    private fun describeVideo(video: InputFile): String = when (video) {
        is InputFile.Url -> "url"
        is InputFile.Bytes -> "bytes(${video.filename},${video.bytes.size})"
        is InputFile.Existing -> "existing"
    }

    private fun preview(text: String): String =
        text.replace("\n", " ").take(60).let { if (text.length > 60) "$it…" else it }

    private fun loadWelcomeResource(): ByteArray? {
        val loader = javaClass.classLoader ?: return null
        val candidates = listOf(
            "start_welcome.jpg",
            "start_welcome.jpg.b64",
            "welcome.jpg",
            "welcome.jpg.b64"
        )
        candidates.forEach { resource ->
            loader.getResourceAsStream(resource)?.use { stream ->
                return when {
                    resource.endsWith(".b64") -> {
                        val encoded = runCatching { stream.readBytes().toString(StandardCharsets.UTF_8) }
                            .getOrNull()
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: return@use
                        runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                    }
                    else -> runCatching { stream.readBytes() }.getOrNull()
                }
            }
        }
        return null
    }
}
