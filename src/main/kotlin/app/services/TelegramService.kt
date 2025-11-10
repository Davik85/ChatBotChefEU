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
import app.services.UIMode
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
        val (finalText, parseMode) = when (config.parseMode) {
            TelegramParseMode.MARKDOWN -> text to ParseMode.MARKDOWN
            TelegramParseMode.HTML -> sanitizeHtml(text)
            else -> text to null
        }
        val sanitizedMarkup = when (replyMarkup) {
            is InlineKeyboardMarkup -> sanitizeMarkup(replyMarkup)
            else -> replyMarkup
        }
        val outbound = OutMessage(
            chatId = chatId,
            text = finalText,
            parseMode = parseMode,
            disableWebPagePreview = true,
            replyMarkup = sanitizedMarkup
        )
        telegramClient.sendMessage(outbound)
    }

    suspend fun sendPhoto(chatId: Long, photo: InputFile, caption: String? = null, replyMarkup: Any? = null) {
        telegramClient.sendPhoto(chatId, photo, caption, replyMarkup)
    }

    suspend fun sendWelcomeImage(chatId: Long) {
        val url = welcomeImageUrl
        if (url != null) {
            runCatching { sendPhoto(chatId, InputFile.Url(url)) }
                .onFailure { logger.warn("Failed to send welcome image from URL: {}", it.message) }
                .onSuccess { return }
        }
        val resourceBytes = loadWelcomeResource()
        if (resourceBytes != null) {
            runCatching {
                sendPhoto(chatId, InputFile.Bytes(filename = "welcome.jpg", bytes = resourceBytes, contentType = "image/jpeg"))
            }.onFailure { logger.warn("Failed to send welcome image from resources: {}", it.message) }
        } else {
            logger.warn("Welcome image resource not found")
        }
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

    fun mainMenuInline(language: String): InlineKeyboardMarkup {
        val recipes = i18n.translate(language, "menu.btn.recipes")
        val calorie = i18n.translate(language, "menu.btn.calorie")
        val macros = i18n.translate(language, "menu.btn.macros")
        val help = i18n.translate(language, "menu.btn.help")
        return InlineKeyboardMarkup(
            listOf(
                listOf(btn(recipes, "mode:${UIMode.RECIPES.name}"), btn(calorie, "mode:${UIMode.CALORIE_CALCULATOR.name}")),
                listOf(btn(macros, "mode:${UIMode.INGREDIENT_MACROS.name}"), btn(help, "mode:${UIMode.HELP.name}"))
            )
        )
    }

    suspend fun sendWelcomeWithMenu(chatId: Long, language: String) {
        sendWelcomeImage(chatId)
        val welcomeText = i18n.translate(language, "menu.start.welcome")
        val markup = mainMenuInline(language)
        runCatching { safeSendMessage(chatId, welcomeText, markup) }
            .onFailure { logger.warn("Failed to send welcome text: {}", it.message) }
    }

    fun modeLabel(language: String, mode: UIMode): String = when (mode) {
        UIMode.RECIPES -> i18n.translate(language, "menu.mode.recipes")
        UIMode.CALORIE_CALCULATOR -> i18n.translate(language, "menu.mode.calorie")
        UIMode.INGREDIENT_MACROS -> i18n.translate(language, "menu.mode.macros")
        UIMode.HELP -> i18n.translate(language, "menu.mode.help")
    }

    suspend fun removeInlineKeyboard(chatId: Long, messageId: Long) {
        val emptyMarkup = InlineKeyboardMarkup(emptyList())
        runCatching { telegramClient.editMessageReplyMarkup(chatId, messageId, emptyMarkup) }
            .onFailure { logger.warn("Failed to remove inline keyboard for chat={} message={}: {}", chatId, messageId, it.message) }
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
        loader.getResourceAsStream("welcome.jpg")?.use { stream ->
            return runCatching { stream.readBytes() }.getOrNull()
        }
        loader.getResourceAsStream("welcome.jpg.b64")?.use { stream ->
            val encoded = runCatching { stream.readBytes().toString(StandardCharsets.UTF_8) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        }
        return null
    }
}
