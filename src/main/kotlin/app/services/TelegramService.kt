package app.services

import app.InlineKeyboardButton
import app.InlineKeyboardMarkup
import app.TelegramConfig
import app.TelegramParseMode
import app.telegram.OutMessage
import app.telegram.ParseMode
import app.telegram.TelegramClient
import org.slf4j.LoggerFactory

class TelegramService(
    private val config: TelegramConfig,
    private val telegramClient: TelegramClient
) {
    private val logger = LoggerFactory.getLogger(TelegramService::class.java)

    suspend fun sendMessage(message: OutMessage) {
        telegramClient.sendMessage(message)
    }

    suspend fun safeSendMessage(chatId: Long, text: String, markup: InlineKeyboardMarkup? = null) {
        val (finalText, parseMode) = when (config.parseMode) {
            TelegramParseMode.MARKDOWNV2 -> text to ParseMode.MARKDOWN_V2
            TelegramParseMode.HTML -> sanitizeHtml(text)
            else -> text to null
        }
        val sanitizedMarkup = sanitizeMarkup(markup)
        val outbound = OutMessage(
            chatId = chatId,
            text = finalText,
            parseMode = parseMode,
            disableWebPagePreview = true,
            replyMarkup = sanitizedMarkup
        )
        telegramClient.sendMessage(outbound)
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

    suspend fun broadcast(adminId: Long, targetIds: List<Long>, message: String, parseMode: String?) {
        logger.info("Admin {} triggered broadcast to {} users", adminId, targetIds.size)
        targetIds.forEach { chatId ->
            val outbound = OutMessage(
                chatId = chatId,
                text = message,
                parseMode = when (parseMode) {
                    "HTML" -> ParseMode.HTML
                    "MarkdownV2" -> ParseMode.MARKDOWN_V2
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
}
