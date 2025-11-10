package app.services

import app.BillingConfig
import app.I18n
import app.LanguageSupport
import app.TelegramParseMode
import app.Update
import app.openai.ChatMessage
import app.openai.OpenAIClient
import app.util.LanguageCallbackAction
import app.util.detectLanguageByGreeting
import app.util.parseLanguageCallbackData
import app.services.prompts.CalorieCalculatorPrompt
import app.services.prompts.PersonaPrompt
import app.services.prompts.ProductInfoPrompt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val COMMAND_GRANT_PREMIUM = "/grantpremium"
private const val COMMAND_PREMIUM_STATUS = "/premiumstatus"
private const val COMMAND_BROADCAST = "/broadcast"
private const val COMMAND_ADMIN_STATS = "/adminstats"
private const val COMMAND_LANGUAGE = "/language"
private const val COMMAND_START = "/start"
private const val COMMAND_HELP = "/help"
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
    private val logger = LoggerFactory.getLogger(UpdateProcessor::class.java)

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
        val language = i18n.resolveLanguage(user.locale)

        if (message.photo != null || message.document != null) {
            sendText(chatId, language, "only_text")
            return
        }

        val text = message.text ?: run {
            sendText(chatId, language, "only_text")
            return
        }

        val trimmedText = text.trim()

        if (user.conversationState == ConversationState.AWAITING_GREETING && !text.startsWith("/")) {
            handleGreetingInput(user, chatId, text, message.from.language_code)
            return
        }

        when {
            trimmedText.startsWith(COMMAND_START) -> handleStart(user, chatId, language)
            trimmedText.startsWith(COMMAND_HELP) -> handleHelp(chatId, language)
            trimmedText.startsWith(COMMAND_LANGUAGE) -> handleLanguageMenu(chatId, language)
            trimmedText.equals("Change language", ignoreCase = true) -> handleLanguageMenu(chatId, language)
            trimmedText.startsWith(COMMAND_PREMIUM_STATUS) -> handlePremiumStatus(chatId, userId, language)
            trimmedText.startsWith(COMMAND_GRANT_PREMIUM) -> handleGrantPremium(userId, trimmedText, language, chatId)
            trimmedText.startsWith(COMMAND_ADMIN_STATS) -> handleAdminStats(userId, chatId, language)
            trimmedText.startsWith(COMMAND_BROADCAST) -> handleBroadcast(userId, trimmedText, language, chatId)
            else -> {
                val labels = menuLabels(language)
                when (trimmedText) {
                    labels.recipes -> handleModeSelection(user, chatId, language, BotMode.RECIPES, labels.recipes)
                    labels.calories -> handleModeSelection(user, chatId, language, BotMode.CALORIES, labels.calories)
                    labels.product -> handleModeSelection(user, chatId, language, BotMode.PRODUCT_INFO, labels.product)
                    labels.help -> handleHelp(chatId, language)
                    else -> handleContentMessage(user, chatId, trimmedText, language)
                }
            }
        }
    }

    private suspend fun handleCallback(callbackId: String, userId: Long, data: String?, chatId: Long?) {
        if (chatId == null) return
        val action = parseLanguageCallbackData(data) ?: return
        val user = userService.ensureUser(userId, null)
        when (action) {
            is LanguageCallbackAction.SetLocale -> handleLanguageSelection(callbackId, chatId, user, action.locale)
            LanguageCallbackAction.RequestOther -> handleLanguageOther(callbackId, chatId, user)
        }
    }

    private suspend fun handleStart(user: UserProfile, chatId: Long, language: String) {
        userService.setMode(user.telegramId, BotMode.NONE)
        telegramService.sendWelcomeImage(chatId)
        val greeting = buildString {
            append(i18n.translate(language, "start.title"))
            append("\n\n")
            append(i18n.translate(language, "start.modes_title"))
        }
        telegramService.safeSendMessage(chatId, greeting, telegramService.mainMenu(language))
        if (user.locale == null) {
            showLanguageMenu(chatId, language)
        }
    }

    private suspend fun handleHelp(chatId: Long, language: String) {
        val helpText = i18n.translate(language, "help.text")
        telegramService.safeSendMessage(chatId, helpText)
    }

    private suspend fun handleLanguageMenu(chatId: Long, language: String) {
        showLanguageMenu(chatId, language)
    }

    private suspend fun handleLanguageSelection(
        callbackId: String,
        chatId: Long,
        user: UserProfile,
        requestedLocale: String
    ) {
        val normalized = normalizeLocale(requestedLocale)
        if (!LanguageSupport.isSupported(normalized)) {
            logger.warn("Unsupported locale {} requested via callback by {}", requestedLocale, user.telegramId)
            telegramService.answerCallback(callbackId, null)
            return
        }
        val locale = normalized!!
        val alreadyActive = user.locale == locale
        val responseLanguage = i18n.resolveLanguage(locale)
        val langName = LanguageSupport.nativeName(locale)
        val key = if (alreadyActive) "lang.already" else "lang.changed"
        val confirmation = i18n.translate(responseLanguage, key, mapOf("langName" to langName))
        telegramService.answerCallback(callbackId, confirmation)
        telegramService.safeSendMessage(chatId, confirmation, telegramService.languageMenu())
        userService.updateConversationState(user.telegramId, null)
        if (!alreadyActive) {
            userService.updateLocale(user.telegramId, locale)
            logger.info("Language for user {} set to {} via callback", user.telegramId, locale)
        }
    }

    private suspend fun handleLanguageOther(callbackId: String, chatId: Long, user: UserProfile) {
        val language = i18n.resolveLanguage(user.locale)
        val title = i18n.translate(language, "lang.other.title")
        telegramService.answerCallback(callbackId, title)
        userService.updateConversationState(user.telegramId, ConversationState.AWAITING_GREETING)
        val prompt = i18n.translate(language, "lang.other.prompt")
        telegramService.safeSendMessage(chatId, prompt)
    }

    private suspend fun handleGreetingInput(
        user: UserProfile,
        chatId: Long,
        text: String,
        fallbackLanguageCode: String?
    ) {
        val language = i18n.resolveLanguage(user.locale)
        val detected = detectLanguageByGreeting(text)
            ?: normalizeLocale(fallbackLanguageCode)
        if (detected == null) {
            val response = i18n.translate(language, "lang.other.unknown")
            telegramService.safeSendMessage(chatId, response)
            return
        }
        if (!LanguageSupport.isSupported(detected)) {
            val fallback = i18n.defaultLanguage()
            userService.updateLocale(user.telegramId, fallback)
            userService.updateConversationState(user.telegramId, null)
            val unsupported = i18n.translate(fallback, "lang.other.unsupported")
            telegramService.safeSendMessage(chatId, unsupported, telegramService.languageMenu())
            logger.warn("Unsupported locale {} detected for user {}", detected, user.telegramId)
            return
        }
        userService.updateLocale(user.telegramId, detected)
        userService.updateConversationState(user.telegramId, null)
        val responseLanguage = i18n.resolveLanguage(detected)
        val confirmation = i18n.translate(responseLanguage, "lang.other.confirm", mapOf("langName" to LanguageSupport.nativeName(detected)))
        telegramService.safeSendMessage(chatId, confirmation, telegramService.languageMenu())
        logger.info("Language for user {} set to {} via greeting", user.telegramId, detected)
    }

    private suspend fun showLanguageMenu(chatId: Long, language: String) {
        val prompt = i18n.translate(language, "menu.language.title")
        telegramService.safeSendMessage(chatId, prompt, telegramService.languageMenu())
    }

    private fun normalizeLocale(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
        return value.substring(0, minOf(2, value.length))
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
            "MARKDOWN" -> TelegramParseMode.MARKDOWN
            "HTML" -> TelegramParseMode.HTML
            else -> null
        }
        val targetIds = userService.listAllUserIds()
        telegramService.broadcast(requesterId, targetIds, message, parseMode)
        val confirmation = i18n.translate(language, "broadcast_ack", mapOf("count" to targetIds.size.toString()))
        telegramService.safeSendMessage(chatId, confirmation)
    }

    private suspend fun handleContentMessage(user: UserProfile, chatId: Long, text: String, language: String) {
        val userId = user.telegramId
        val mode = userService.getMode(userId) ?: user.mode ?: BotMode.NONE
        if (mode == BotMode.NONE) {
            val reminder = i18n.translate(language, "start.modes_title")
            telegramService.safeSendMessage(chatId, reminder, telegramService.mainMenu(language))
            return
        }

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

        val systemPrompt = when (mode) {
            BotMode.RECIPES -> PersonaPrompt.system()
            BotMode.CALORIES -> CalorieCalculatorPrompt.SYSTEM
            BotMode.PRODUCT_INFO -> ProductInfoPrompt.SYSTEM
            BotMode.NONE -> i18n.translate(language, "system_prompt")
        }

        val messages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            history.forEach { stored ->
                add(ChatMessage(role = stored.role, content = stored.content))
            }
            add(ChatMessage(role = ROLE_USER, content = text))
        }
        val completion = openAIClient.complete(messages)
        val responseText = if (completion != null) {
            completion.trim()
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

    private suspend fun handleModeSelection(
        user: UserProfile,
        chatId: Long,
        language: String,
        mode: BotMode,
        modeLabel: String
    ) {
        userService.setMode(user.telegramId, mode)
        val removedMessage = i18n.translate(language, "menu.removed", mapOf("name" to modeLabel))
        telegramService.safeSendMessage(chatId, removedMessage, telegramService.removeKeyboard())
        val onboardKey = onboardingKey(mode)
        if (onboardKey != null) {
            val onboarding = i18n.translate(language, onboardKey)
            telegramService.safeSendMessage(chatId, onboarding)
        }
    }

    private fun onboardingKey(mode: BotMode): String? = when (mode) {
        BotMode.RECIPES -> "mode.recipes.onboard"
        BotMode.CALORIES -> "mode.calories.onboard"
        BotMode.PRODUCT_INFO -> "mode.product.onboard"
        BotMode.NONE -> null
    }

    private data class MenuLabels(
        val recipes: String,
        val calories: String,
        val product: String,
        val help: String
    )

    private fun menuLabels(language: String): MenuLabels = MenuLabels(
        recipes = i18n.translate(language, "menu.btn.recipes"),
        calories = i18n.translate(language, "menu.btn.calorie_calc"),
        product = i18n.translate(language, "menu.btn.product_info"),
        help = i18n.translate(language, "menu.btn.help")
    )
}
