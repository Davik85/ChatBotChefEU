package app.services

import app.BillingConfig
import app.I18n
import app.LanguageMenu
import app.Update
import app.openai.ChatMessage
import app.openai.OpenAIClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val COMMAND_GRANT_PREMIUM = "/grantpremium"
private const val COMMAND_PREMIUM_STATUS = "/premiumstatus"
private const val COMMAND_BROADCAST = "/broadcast"
private const val COMMAND_ADMIN_STATS = "/adminstats"
private const val COMMAND_LANGUAGE = "/language"
private const val COMMAND_START = "/start"
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"))

class UpdateProcessor(
    private val i18n: I18n,
    private val userService: UserService,
    private val premiumService: PremiumService,
    private val usageService: UsageService,
    private val messageHistoryService: MessageHistoryService,
    private val telegramService: TelegramService,
    private val openAIClient: OpenAIClient,
    private val billingConfig: BillingConfig,
    private val adminService: AdminService,
    private val adminIds: Set<Long>
) : UpdateHandler {
    override suspend fun handle(update: Update) {
        update.callbackQuery?.let { callback ->
            handleCallback(callback.id, callback.from.id, callback.data, callback.message?.chat?.id)
            return
        }
        val message = update.message ?: return
        if (message.from == null) return
        val chatId = message.chat.id
        val userId = message.from.id
        val user = userService.ensureUser(userId, message.from.language_code)
        val language = i18n.resolveLanguage(user.language)

        if (message.photo != null || message.document != null) {
            sendText(chatId, language, "only_text")
            return
        }

        val text = message.text ?: run {
            sendText(chatId, language, "only_text")
            return
        }
        when {
            text.startsWith(COMMAND_START) -> handleStart(chatId, language)
            text.startsWith(COMMAND_LANGUAGE) -> handleLanguageMenu(chatId, language)
            text.startsWith(COMMAND_PREMIUM_STATUS) -> handlePremiumStatus(chatId, userId, language)
            text.startsWith(COMMAND_GRANT_PREMIUM) -> handleGrantPremium(userId, text, language, chatId)
            text.startsWith(COMMAND_ADMIN_STATS) -> handleAdminStats(userId, chatId, language)
            text.startsWith(COMMAND_BROADCAST) -> handleBroadcast(userId, text, language, chatId)
            else -> handleContentMessage(userId, chatId, text, language)
        }
    }

    private suspend fun handleCallback(callbackId: String, userId: Long, data: String?, chatId: Long?) {
        if (data == null || chatId == null) return
        if (!data.startsWith("lang:")) return
        val requestedLanguage = data.removePrefix("lang:")
        val languageCode = i18n.resolveLanguage(requestedLanguage)
        userService.updateLanguage(userId, languageCode)
        val confirmation = i18n.translate(languageCode, "language_saved")
        telegramService.answerCallback(callbackId, confirmation)
        telegramService.safeSendMessage(chatId, confirmation)
    }

    private suspend fun handleStart(chatId: Long, language: String) {
        val text = i18n.translate(language, "start_message")
        val menuText = i18n.translate(language, "choose_language")
        telegramService.safeSendMessage(chatId, "$text\n\n$menuText")
    }

    private suspend fun handleLanguageMenu(chatId: Long, language: String) {
        val text = i18n.translate(language, "choose_language")
        telegramService.safeSendMessage(chatId, text, LanguageMenu.buildMenu())
    }

    private suspend fun handlePremiumStatus(chatId: Long, userId: Long, language: String) {
        val activeUntil = premiumService.getPremiumUntil(userId)
        val response = if (activeUntil != null && activeUntil.isAfter(Instant.now())) {
            i18n.translate(language, "premium_status_active", mapOf("date" to DATE_FORMATTER.format(activeUntil)))
        } else {
            i18n.translate(language, "premium_status_inactive")
        }
        telegramService.safeSendMessage(chatId, response)
    }

    private suspend fun handleGrantPremium(requesterId: Long, text: String, language: String, chatId: Long) {
        if (!adminIds.contains(requesterId)) {
            sendText(chatId, language, "not_authorized")
            return
        }
        val parts = text.split(" ")
        if (parts.size < 3) {
            sendText(chatId, language, "premium_invalid_command")
            return
        }
        val targetId = parts[1].toLongOrNull()
        val days = parts[2].toLongOrNull()
        if (targetId == null || days == null) {
            sendText(chatId, language, "premium_invalid_command")
            return
        }
        val expiry = premiumService.grantPremium(targetId, days)
        val message = i18n.translate(language, "premium_granted", mapOf("date" to DATE_FORMATTER.format(expiry)))
        telegramService.safeSendMessage(chatId, message)
    }

    private suspend fun handleAdminStats(requesterId: Long, chatId: Long, language: String) {
        if (!adminIds.contains(requesterId)) {
            sendText(chatId, language, "not_authorized")
            return
        }
        val stats = adminService.collectStats()
        val message = i18n.translate(
            language,
            "admin_stats",
            mapOf(
                "users" to stats.totalUsers.toString(),
                "premium" to stats.premiumUsers.toString(),
                "dau" to stats.dau7.toString()
            )
        )
        telegramService.safeSendMessage(chatId, message)
    }

    private suspend fun handleBroadcast(requesterId: Long, text: String, language: String, chatId: Long) {
        if (!adminIds.contains(requesterId)) {
            sendText(chatId, language, "not_authorized")
            return
        }
        val payload = text.removePrefix(COMMAND_BROADCAST).trim()
        if (payload.isBlank()) {
            sendText(chatId, language, "broadcast_usage")
            return
        }
        val parts = payload.split(" ", limit = 2)
        val (modeCandidate, message) = if (parts.size == 2 && (parts[0].equals("markdown", true) || parts[0].equals("html", true))) {
            parts[0].uppercase() to parts[1]
        } else {
            null to payload
        }
        if (message.isBlank()) {
            sendText(chatId, language, "broadcast_usage")
            return
        }
        val parseMode = when (modeCandidate) {
            "MARKDOWN" -> "MarkdownV2"
            "HTML" -> "HTML"
            else -> null
        }
        val targetIds = userService.listAllUserIds()
        telegramService.broadcast(requesterId, targetIds, message, parseMode)
        val confirmation = i18n.translate(language, "broadcast_ack", mapOf("count" to targetIds.size.toString()))
        telegramService.safeSendMessage(chatId, confirmation)
    }

    private suspend fun handleContentMessage(userId: Long, chatId: Long, text: String, language: String) {
        val premium = premiumService.isPremiumActive(userId)
        if (!premium) {
            val usage = usageService.getUsage(userId)
            if (usage >= billingConfig.freeTotalLimit) {
                val message = i18n.translate(
                    language,
                    "limit_reached",
                    mapOf(
                        "limit" to billingConfig.freeTotalLimit.toString(),
                        "price" to billingConfig.premiumPrice.toPlainString(),
                        "duration" to billingConfig.premiumDurationDays.toString()
                    )
                )
                telegramService.safeSendMessage(chatId, message)
                return
            }
        }

        usageService.incrementUsage(userId)
        val history = messageHistoryService.loadRecent(userId)
        messageHistoryService.append(userId, ROLE_USER, text)

        val keywords = i18n.keywords(language, "calorie_keywords")
        val intent = if (keywords.any { text.lowercase().contains(it) }) "calories" else "recipe"
        val prefixKey = if (intent == "calories") "calorie_prefix" else "recipe_prefix"
        val intro = i18n.translate(language, "chef_intro")

        val messages = buildList {
            add(ChatMessage(role = "system", content = i18n.translate(language, "system_prompt")))
            history.forEach { stored ->
                add(ChatMessage(role = stored.role, content = stored.content))
            }
            add(ChatMessage(role = ROLE_USER, content = text))
        }
        val completion = openAIClient.complete(messages)
        val responseText = if (completion != null) {
            val prefix = i18n.translate(language, prefixKey)
            "$intro\n$prefix ${completion.trim()}"
        } else {
            i18n.translate(language, "ai_error")
        }
        telegramService.safeSendMessage(chatId, responseText)
        messageHistoryService.append(userId, ROLE_ASSISTANT, responseText)
    }

    private suspend fun sendText(chatId: Long, language: String, key: String) {
        val text = i18n.translate(language, key)
        telegramService.safeSendMessage(chatId, text)
    }
}
