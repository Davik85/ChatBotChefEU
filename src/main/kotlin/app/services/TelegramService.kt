package app.services

import app.I18n
import app.InlineKeyboardButton
import app.InlineKeyboardMarkup
import app.InputFile
import app.LanguageSupport
import app.Message
import app.TelegramConfig
import app.TelegramParseMode
import app.telegram.OutMessage
import app.telegram.ParseMode
import app.telegram.TelegramClient
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.slf4j.LoggerFactory

class TelegramService(
    private val config: TelegramConfig,
    private val telegramClient: TelegramClient,
    private val i18n: I18n
) {
    private val logger = LoggerFactory.getLogger(TelegramService::class.java)
    private val welcomeImageUrl = config.welcomeImageUrl?.takeIf { it.isNotBlank() }

    suspend fun sendMessage(message: OutMessage) {
        telegramClient.sendMessage(message)
    }

    suspend fun safeSendMessage(chatId: Long, text: String, replyMarkup: Any? = null) {
        val outbound = buildOutbound(chatId, text, replyMarkup)
        telegramClient.sendMessage(outbound)
    }

    suspend fun sendPhoto(chatId: Long, photo: InputFile, caption: String? = null, replyMarkup: Any? = null): Message? {
        return telegramClient.sendPhoto(chatId, photo, caption, replyMarkup)
    }

    suspend fun sendWelcomeImage(chatId: Long): Long? {
        val url = welcomeImageUrl
        if (url != null) {
            val message = runCatching { sendPhoto(chatId, InputFile.Url(url)) }
                .onFailure { logger.warn("Failed to send welcome image from URL: {}", it.message) }
                .getOrNull()
            if (message != null) {
                return message.message_id
            }
        }
        val resourceBytes = loadWelcomeResource()
        if (resourceBytes != null) {
            val message = runCatching {
                sendPhoto(
                    chatId,
                    InputFile.Bytes(filename = "welcome.jpg", bytes = resourceBytes, contentType = "image/jpeg")
                )
            }.onFailure { logger.warn("Failed to send welcome image from resources: {}", it.message) }
                .getOrNull()
            if (message != null) {
                return message.message_id
            }
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
        telegramClient.answerCallback(callbackId, text)
    }

    fun languageMenu(): InlineKeyboardMarkup = InlineKeyboardMarkup(
        listOf(
            listOf(
                btn(LanguageSupport.inlineLabel("en"), "lang:set:en"),
                btn(LanguageSupport.inlineLabel("de"), "lang:set:de"),
                btn(LanguageSupport.inlineLabel("it"), "lang:set:it")
            ),
            listOf(
                btn(LanguageSupport.inlineLabel("es"), "lang:set:es"),
                btn(LanguageSupport.inlineLabel("fr"), "lang:set:fr"),
                btn("\uD83C\uDF0D Other", "lang:other")
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

    suspend fun sendMainMenu(chatId: Long, text: String, language: String): Long? {
        val outbound = buildOutbound(chatId, text, mainMenuKeyboard(language))
        val response = telegramClient.sendMessage(outbound)
        return response?.message_id
    }

    suspend fun removeInlineKeyboard(chatId: Long, messageId: Long) {
        val emptyMarkup = InlineKeyboardMarkup(emptyList())
        runCatching { telegramClient.editMessageReplyMarkup(chatId, messageId, emptyMarkup) }
            .onFailure { logger.warn("Failed to remove inline keyboard for chat={} message={}: {}", chatId, messageId, it.message) }
    }

    suspend fun deleteMessage(chatId: Long, messageId: Long) {
        runCatching { telegramClient.deleteMessage(chatId, messageId) }
            .onFailure { logger.warn("Failed to delete message chat={} message={} : {}", chatId, messageId, it.message) }
    }

    suspend fun broadcast(adminId: Long, targetIds: List<Long>, message: String, parseMode: TelegramParseMode?) {
        logger.info("Admin {} triggered broadcast to {} users", adminId, targetIds.size)
        targetIds.forEach { chatId ->
            val outbound = OutMessage(
                chatId = chatId,
                text = message,
                parseMode = when (parseMode) {
                    TelegramParseMode.HTML -> ParseMode.HTML
                    TelegramParseMode.MARKDOWN -> ParseMode.MARKDOWN
                    else -> null
                },
                disableWebPagePreview = true
            )
            runCatching { telegramClient.sendMessage(outbound) }
                .onFailure { logger.warn("Broadcast to {} failed: {}", chatId, it.message) }
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
