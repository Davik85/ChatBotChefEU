package app.services

import app.BillingConfig
import app.HelpConfig
import app.I18n
import app.LanguageSupport
import app.TelegramUser
import app.Update
import app.openai.ChatMessage
import app.openai.OpenAIClient
import app.prompts.Prompts
import app.services.ConversationMode
import app.util.AdminCallbackAction
import app.util.LanguageCallbackAction
import app.util.detectLanguageByGreeting
import app.util.detectLanguageByName
import app.util.parseAdminCallbackData
import app.util.parseLanguageCallbackData
import app.util.MainMenuAction
import app.util.parseMainMenuCallbackData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import org.slf4j.LoggerFactory

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val COMMAND_ADMIN = "/admin"
private const val COMMAND_WHOAMI = "/whoami"
private const val COMMAND_PREMIUM_STATUS = "/premiumstatus"
private const val COMMAND_LANGUAGE = "/language"
private const val COMMAND_START = "/start"
private const val COMMAND_HELP = "/help"
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"))
private val DATE_TIME_FORMATTER = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    .appendLiteral(' ')
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .toFormatter()
    .withZone(ZoneId.of("UTC"))

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
    private val adminConversationStateService: AdminConversationStateService,
    private val broadcastService: BroadcastService,
    private val adminIds: Set<Long>,
    private val helpConfig: HelpConfig
) : UpdateHandler {
    private val logger = LoggerFactory.getLogger(UpdateProcessor::class.java)

    override suspend fun handle(update: Update) {
        update.callbackQuery?.let { callback ->
            handleCallback(
                callback.id,
                callback.from.id,
                callback.data,
                callback.message?.chat?.id,
                callback.message?.message_id
            )
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
            trimmedText.startsWith(COMMAND_START) -> handleStart(user, chatId, language, message.message_id)
            trimmedText.startsWith(COMMAND_HELP) -> handleHelp(chatId, language)
            trimmedText.startsWith(COMMAND_LANGUAGE) -> handleLanguageMenu(chatId, language)
            trimmedText.equals("Change language", ignoreCase = true) -> handleLanguageMenu(chatId, language)
            trimmedText.startsWith(COMMAND_PREMIUM_STATUS) -> handlePremiumStatus(chatId, userId, language)
            trimmedText.startsWith(COMMAND_ADMIN) -> {
                handleAdminCommand(userId, chatId, language)
            }
            trimmedText.startsWith(COMMAND_WHOAMI) -> handleWhoAmI(chatId, message.from, language)
            else -> {
                if (handleAdminConversationMessage(user, chatId, trimmedText, language)) {
                    return
                }
                handleContentMessage(user, chatId, trimmedText, language)
            }
        }
    }

    private suspend fun handleCallback(
        callbackId: String,
        userId: Long,
        data: String?,
        chatId: Long?,
        messageId: Long?
    ) {
        if (chatId == null || data.isNullOrBlank()) return
        val mainMenuAction = parseMainMenuCallbackData(data)
        if (mainMenuAction != null) {
            handleMainMenuSelection(callbackId, chatId, messageId, userId, mainMenuAction)
            return
        }
        val adminAction = parseAdminCallbackData(data)
        if (adminAction != null) {
            handleAdminCallback(callbackId, userId, chatId, adminAction)
            return
        }
        val action = parseLanguageCallbackData(data) ?: return
        val user = userService.ensureUser(userId, null)
        when (action) {
            is LanguageCallbackAction.SetLocale -> handleLanguageSelection(callbackId, chatId, messageId, user, action.locale)
            LanguageCallbackAction.RequestOther -> handleLanguageOther(callbackId, chatId, messageId, user)
        }
    }

    private suspend fun handleStart(user: UserProfile, chatId: Long, language: String, startMessageId: Long?) {
        clearStartSequence(user, chatId, deleteStartCommand = false)
        userService.updateMode(user.telegramId, null)
        user.mode = null
        userService.updateConversationState(user.telegramId, null)
        showMainMenu(user, chatId, language, startMessageId = startMessageId, includeImage = true)
        if (user.locale == null) {
            showLanguageMenu(chatId, language)
        }
    }

    private suspend fun handleHelp(chatId: Long, language: String) {
        telegramService.safeSendMessage(chatId, helpMessage(language))
    }

    private suspend fun handleLanguageMenu(chatId: Long, language: String) {
        showLanguageMenu(chatId, language)
    }

    private suspend fun handleLanguageSelection(
        callbackId: String,
        chatId: Long,
        messageId: Long?,
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
        if (messageId != null) {
            telegramService.removeInlineKeyboard(chatId, messageId)
        }
        telegramService.safeSendMessage(chatId, confirmation)
        userService.updateMode(user.telegramId, null)
        user.mode = null
        showMainMenu(user, chatId, responseLanguage, includeImage = true)
        userService.updateConversationState(user.telegramId, null)
        if (!alreadyActive) {
            userService.updateLocale(user.telegramId, locale)
            logger.info("Language for user {} set to {} via callback", user.telegramId, locale)
        }
    }

    private suspend fun handleLanguageOther(
        callbackId: String,
        chatId: Long,
        messageId: Long?,
        user: UserProfile
    ) {
        val language = i18n.resolveLanguage(user.locale)
        val title = i18n.translate(language, "lang.other.title")
        telegramService.answerCallback(callbackId, title)
        userService.updateConversationState(user.telegramId, ConversationState.AWAITING_GREETING)
        val prompt = i18n.translate(language, "lang.other.prompt")
        if (messageId != null) {
            telegramService.removeInlineKeyboard(chatId, messageId)
        }
        telegramService.safeSendMessage(chatId, prompt)
    }

    private suspend fun handleGreetingInput(
        user: UserProfile,
        chatId: Long,
        text: String,
        fallbackLanguageCode: String?
    ) {
        val language = i18n.resolveLanguage(user.locale)
        val supportedLanguages = LanguageSupport.supportedLanguageList()
        val detected = detectLanguageByGreeting(text)
            ?: detectLanguageByName(text)
            ?: normalizeLocale(fallbackLanguageCode)
        if (detected == null) {
            val response = i18n.translate(
                language,
                "lang.other.unknown",
                mapOf("languages" to supportedLanguages)
            )
            telegramService.safeSendMessage(chatId, response)
            return
        }
        if (!LanguageSupport.isSupported(detected)) {
            val fallback = i18n.defaultLanguage()
            userService.updateLocale(user.telegramId, fallback)
            userService.updateConversationState(user.telegramId, null)
            val unsupported = i18n.translate(
                fallback,
                "lang.other.unsupported",
                mapOf("languages" to supportedLanguages)
            )
            telegramService.safeSendMessage(chatId, unsupported)
            user.mode = null
            userService.updateMode(user.telegramId, null)
            showMainMenu(user, chatId, fallback, includeImage = true)
            logger.warn("Unsupported locale {} detected for user {}", detected, user.telegramId)
            return
        }
        userService.updateLocale(user.telegramId, detected)
        userService.updateConversationState(user.telegramId, null)
        val responseLanguage = i18n.resolveLanguage(detected)
        val confirmation = i18n.translate(
            responseLanguage,
            "lang.other.confirm",
            mapOf("langName" to LanguageSupport.nativeName(detected))
        )
        telegramService.safeSendMessage(chatId, confirmation)
        user.mode = null
        userService.updateMode(user.telegramId, null)
        showMainMenu(user, chatId, responseLanguage, includeImage = true)
        logger.info("Language for user {} set to {} via greeting", user.telegramId, detected)
    }

    private suspend fun handleMainMenuSelection(
        callbackId: String,
        chatId: Long,
        messageId: Long?,
        userId: Long,
        action: MainMenuAction
    ) {
        val user = userService.ensureUser(userId, null)
        val language = i18n.resolveLanguage(user.locale)
        if (messageId != null) {
            user.lastMenuMessageId = messageId
            userService.updateLastMenuMessageId(user.telegramId, messageId)
        }
        if (action == MainMenuAction.Help) {
            telegramService.answerCallback(callbackId, null)
            telegramService.safeSendMessage(chatId, helpMessage(language))
            return
        }

        val (mode, activationKey) = when (action) {
            MainMenuAction.Recipes -> ConversationMode.RECIPES to "mode.recipes.activated"
            MainMenuAction.Calorie -> ConversationMode.CALORIE to "mode.calorie.activated"
            MainMenuAction.Ingredient -> ConversationMode.INGREDIENT to "mode.ingredient.activated"
            MainMenuAction.Help -> error("Help action should have been handled before mode activation")
        }
        activateMode(callbackId, chatId, user, userId, language, mode, activationKey)
    }

    private suspend fun activateMode(
        callbackId: String,
        chatId: Long,
        user: UserProfile,
        userId: Long,
        language: String,
        mode: ConversationMode,
        activationKey: String
    ) {
        userService.updateMode(userId, mode)
        user.mode = mode
        userService.updateConversationState(userId, null)
        messageHistoryService.clear(userId)
        logger.info("User {} switched to {}", userId, mode)
        telegramService.answerCallback(callbackId, null)
        clearStartSequence(user, chatId)
        val activationMessage = i18n.translate(language, activationKey)
        telegramService.safeSendMessage(chatId, activationMessage)
    }

    private suspend fun showMainMenu(
        user: UserProfile,
        chatId: Long,
        language: String,
        startMessageId: Long? = user.lastStartCommandMessageId,
        includeImage: Boolean = true
    ) {
        val effectiveStartMessageId = startMessageId ?: user.lastStartCommandMessageId
        if (user.lastStartCommandMessageId != effectiveStartMessageId) {
            user.lastStartCommandMessageId = effectiveStartMessageId
            userService.updateLastStartCommandMessageId(user.telegramId, effectiveStartMessageId)
        }

        if (includeImage) {
            val sentImageId = telegramService.sendWelcomeImage(chatId)
            user.lastWelcomeImageMessageId = sentImageId
            userService.updateLastWelcomeImageMessageId(user.telegramId, sentImageId)
        }

        val greeting = i18n.translate(language, "start.greeting")
        val greetingMessageId = telegramService.safeSendMessage(chatId, greeting)
        user.lastWelcomeGreetingMessageId = greetingMessageId
        userService.updateLastWelcomeGreetingMessageId(user.telegramId, greetingMessageId)

        val menuTitle = i18n.translate(language, "menu.main.title")
        val keyboard = telegramService.mainMenuKeyboard(language)
        val menuMessageId = telegramService.safeSendMessage(chatId, menuTitle, keyboard)
        user.lastMenuMessageId = menuMessageId
        userService.updateLastMenuMessageId(user.telegramId, menuMessageId)
    }

    private suspend fun clearStartSequence(user: UserProfile, chatId: Long, deleteStartCommand: Boolean = true) {
        val imageId = user.lastWelcomeImageMessageId
        if (imageId != null) {
            telegramService.deleteMessage(chatId, imageId)
            user.lastWelcomeImageMessageId = null
            userService.updateLastWelcomeImageMessageId(user.telegramId, null)
        }

        val greetingId = user.lastWelcomeGreetingMessageId
        if (greetingId != null) {
            telegramService.deleteMessage(chatId, greetingId)
            user.lastWelcomeGreetingMessageId = null
            userService.updateLastWelcomeGreetingMessageId(user.telegramId, null)
        }

        val menuId = user.lastMenuMessageId
        if (menuId != null) {
            telegramService.deleteMessage(chatId, menuId)
            user.lastMenuMessageId = null
            userService.updateLastMenuMessageId(user.telegramId, null)
        }

        if (deleteStartCommand) {
            val startId = user.lastStartCommandMessageId
            if (startId != null) {
                telegramService.deleteMessage(chatId, startId)
                user.lastStartCommandMessageId = null
                userService.updateLastStartCommandMessageId(user.telegramId, null)
            }
        }
    }

    private suspend fun showLanguageMenu(chatId: Long, language: String) {
        val prompt = i18n.translate(language, "menu.language.title")
        telegramService.safeSendMessage(chatId, prompt, telegramService.languageMenu(language))
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

    private suspend fun handleWhoAmI(chatId: Long, from: TelegramUser?, language: String) {
        val user = from ?: return
        val username = user.username?.let { "@${it}" } ?: "-"
        val firstName = user.first_name?.takeIf { it.isNotBlank() } ?: "-"
        val message = i18n.translate(
            language,
            "whoami.you_are",
            mapOf(
                "id" to user.id.toString(),
                "username" to username,
                "first_name" to firstName
            )
        )
        telegramService.safeSendMessage(chatId, message)
    }

    private suspend fun handleAdminCommand(userId: Long, chatId: Long, language: String) {
        if (!isAdmin(userId)) {
            logger.warn("Non-admin {} attempted to access /admin", userId)
            sendText(chatId, language, "not_authorized")
            return
        }
        clearAdminState(userId)
        val title = i18n.translate(language, "admin.menu.title")
        val keyboard = telegramService.adminMenu(language)
        telegramService.safeSendMessage(chatId, title, keyboard)
        logger.info("Admin {} opened admin menu", userId)
    }

    private suspend fun handleAdminCallback(
        callbackId: String,
        userId: Long,
        chatId: Long,
        action: AdminCallbackAction
    ) {
        val user = userService.ensureUser(userId, null)
        val language = i18n.resolveLanguage(user.locale)
        if (!isAdmin(userId)) {
            val warning = i18n.translate(language, "not_authorized")
            telegramService.answerCallback(callbackId, warning)
            logger.warn("Non-admin {} triggered admin callback {}", userId, action)
            return
        }
        telegramService.answerCallback(callbackId, null)
        when (action) {
            AdminCallbackAction.Stats -> handleAdminStatsAction(userId, chatId, language)
            AdminCallbackAction.Broadcast -> handleAdminBroadcastPrompt(userId, chatId, language)
            AdminCallbackAction.BroadcastSend -> handleAdminBroadcastSend(userId, chatId, language)
            AdminCallbackAction.Cancel -> handleAdminCancel(userId, chatId, language)
            AdminCallbackAction.UserStatus -> handleAdminUserStatusPrompt(userId, chatId, language)
            AdminCallbackAction.GrantPremium -> handleAdminGrantPrompt(userId, chatId, language)
            AdminCallbackAction.LanguageStats -> handleAdminLanguageStats(userId, chatId, language)
        }
    }

    private suspend fun handleAdminStatsAction(userId: Long, chatId: Long, language: String) {
        val stats = adminService.collectOverview()
        val blockedValue = stats.blockedUsers?.toString() ?: i18n.translate(language, "admin.common.not_available")
        val message = listOf(
            i18n.translate(language, "admin.stats.total", mapOf("value" to stats.totalUsers.toString())),
            i18n.translate(language, "admin.stats.active7", mapOf("value" to stats.active7Days.toString())),
            i18n.translate(language, "admin.stats.active30", mapOf("value" to stats.active30Days.toString())),
            i18n.translate(language, "admin.stats.premium", mapOf("value" to stats.activePremiumUsers.toString())),
            i18n.translate(language, "admin.stats.blocked", mapOf("value" to blockedValue))
        ).joinToString(separator = "\n")
        telegramService.safeSendMessage(chatId, message)
        logger.info("Admin {} requested stats", userId)
    }

    private suspend fun handleAdminBroadcastPrompt(userId: Long, chatId: Long, language: String) {
        activateAdminState(userId, ConversationState.ADMIN_AWAITING_BROADCAST_TEXT, AdminConversationState.AwaitingBroadcastText)
        sendText(chatId, language, "admin.broadcast.prompt")
        logger.info("Admin {} awaiting broadcast text", userId)
    }

    private suspend fun handleAdminBroadcastInput(
        user: UserProfile,
        chatId: Long,
        text: String,
        language: String
    ) {
        val content = text.trim()
        if (content.isEmpty()) {
            sendText(chatId, language, "admin.broadcast.validation_empty")
            return
        }
        activateAdminState(
            user.telegramId,
            ConversationState.ADMIN_CONFIRM_BROADCAST,
            AdminConversationState.BroadcastPreview(content)
        )
        val previewHeader = i18n.translate(language, "admin.broadcast.preview")
        val keyboard = telegramService.adminBroadcastPreviewKeyboard(language)
        telegramService.safeSendMessage(chatId, "$previewHeader\n\n$content", keyboard)
        logger.info("Admin {} prepared broadcast preview", user.telegramId)
    }

    private suspend fun handleAdminBroadcastSend(userId: Long, chatId: Long, language: String) {
        val state = adminConversationStateService.get(userId)
        if (state !is AdminConversationState.BroadcastPreview) {
            sendText(chatId, language, "admin.broadcast.nothing_to_send")
            clearAdminState(userId)
            logger.warn("Admin {} attempted to send broadcast without payload", userId)
            return
        }
        clearAdminState(userId)
        val targetIds = userService.listAllUserIds()
        val result = broadcastService.dispatch(userId, targetIds, state.text)
        val message = i18n.translate(
            language,
            "admin.broadcast.result",
            mapOf(
                "delivered" to result.delivered.toString(),
                "failed" to result.failed.toString(),
                "total" to result.total.toString()
            )
        )
        telegramService.safeSendMessage(chatId, message)
        logger.info(
            "Admin {} broadcast summary delivered={} failed={} total={}",
            userId,
            result.delivered,
            result.failed,
            result.total
        )
    }

    private suspend fun handleAdminCancel(userId: Long, chatId: Long, language: String) {
        clearAdminState(userId)
        sendText(chatId, language, "admin.common.cancelled")
        logger.info("Admin {} cancelled current action", userId)
    }

    private suspend fun handleAdminUserStatusPrompt(userId: Long, chatId: Long, language: String) {
        activateAdminState(userId, ConversationState.ADMIN_AWAITING_USER_STATUS, AdminConversationState.AwaitingUserStatus)
        sendText(chatId, language, "admin.user_status.prompt")
        logger.info("Admin {} requested user status", userId)
    }

    private suspend fun handleAdminUserStatusInput(
        user: UserProfile,
        chatId: Long,
        text: String,
        language: String
    ) {
        val targetId = text.trim().toLongOrNull()
        if (targetId == null) {
            sendText(chatId, language, "admin.validation.invalid_user_id")
            return
        }
        val status = adminService.findUserStatus(targetId)
        if (status == null) {
            sendText(chatId, language, "admin.validation.not_found")
            return
        }
        clearAdminState(user.telegramId)
        val now = Instant.now()
        val premiumUntil = status.premiumUntil?.takeIf { it.isAfter(now) }
        val premiumLine = if (premiumUntil != null) {
            i18n.translate(
                language,
                "admin.user_status.result.premium_yes",
                mapOf("date" to DATE_FORMATTER.format(premiumUntil))
            )
        } else {
            i18n.translate(language, "admin.user_status.result.premium_no")
        }
        val lastActivity = status.lastActivity?.let { DATE_TIME_FORMATTER.format(it) }
            ?: i18n.translate(language, "admin.common.not_available")
        val localeValue = status.locale?.takeIf { it.isNotBlank() }
            ?: i18n.translate(language, "admin.common.not_available")
        val message = listOf(
            i18n.translate(language, "admin.user_status.result.title", mapOf("userId" to targetId.toString())),
            premiumLine,
            i18n.translate(language, "admin.user_status.result.last_activity", mapOf("value" to lastActivity)),
            i18n.translate(language, "admin.user_status.result.locale", mapOf("value" to localeValue))
        ).joinToString("\n")
        telegramService.safeSendMessage(chatId, message)
        logger.info("Admin {} inspected user {}", user.telegramId, targetId)
    }

    private suspend fun handleAdminGrantPrompt(userId: Long, chatId: Long, language: String) {
        activateAdminState(userId, ConversationState.ADMIN_AWAITING_GRANT_PREMIUM, AdminConversationState.AwaitingGrantPremium)
        sendText(chatId, language, "admin.grant.prompt")
        logger.info("Admin {} started grant premium flow", userId)
    }

    private suspend fun handleAdminGrantInput(
        user: UserProfile,
        chatId: Long,
        text: String,
        language: String
    ) {
        val parts = text.trim().split(" ")
        if (parts.size < 2) {
            sendText(chatId, language, "admin.validation.invalid_args")
            return
        }
        val targetId = parts[0].toLongOrNull()
        val days = parts[1].toLongOrNull()
        if (targetId == null || days == null || days <= 0) {
            sendText(chatId, language, "admin.validation.invalid_args")
            return
        }
        val target = userService.findUser(targetId)
        if (target == null) {
            sendText(chatId, language, "admin.validation.not_found")
            return
        }
        val expiry = premiumService.grantPremium(targetId, days)
        clearAdminState(user.telegramId)
        val expiryText = DATE_FORMATTER.format(expiry)
        val confirmation = i18n.translate(language, "admin.grant.ok", mapOf("date" to expiryText))
        telegramService.safeSendMessage(chatId, confirmation)
        val targetLanguage = i18n.resolveLanguage(target.locale)
        val userMessage = i18n.translate(targetLanguage, "user.premium.granted", mapOf("date" to expiryText))
        telegramService.safeSendMessage(targetId, userMessage)
        logger.info("Admin {} granted premium to {} for {} days", user.telegramId, targetId, days)
    }

    private suspend fun handleAdminLanguageStats(userId: Long, chatId: Long, language: String) {
        val stats = adminService.collectLanguageStats()
        if (stats.isEmpty()) {
            sendText(chatId, language, "admin.lang_stats.empty")
            return
        }
        val title = i18n.translate(language, "admin.lang_stats.title")
        val unknownLabel = i18n.translate(language, "admin.lang_stats.unknown")
        val lines = stats.map { stat ->
            val localeLabel = if (stat.locale == "unknown") unknownLabel else stat.locale
            i18n.translate(
                language,
                "admin.lang_stats.item",
                mapOf(
                    "locale" to localeLabel,
                    "count" to stat.count.toString()
                )
            )
        }
        val message = buildList {
            add(title)
            addAll(lines)
        }.joinToString("\n")
        telegramService.safeSendMessage(chatId, message)
        logger.info("Admin {} requested language stats", userId)
    }

    private suspend fun handleAdminConversationMessage(
        user: UserProfile,
        chatId: Long,
        text: String,
        language: String
    ): Boolean {
        val state = user.conversationState
        if (!isAdminConversationState(state)) {
            return false
        }
        if (!isAdmin(user.telegramId)) {
            clearAdminState(user.telegramId)
            return false
        }
        val adminState = adminConversationStateService.get(user.telegramId)
        if (adminState == null) {
            clearAdminState(user.telegramId)
            sendText(chatId, language, "admin.common.expired")
            logger.info("Admin {} conversation state expired", user.telegramId)
            return true
        }
        return when (state) {
            ConversationState.ADMIN_AWAITING_BROADCAST_TEXT, ConversationState.ADMIN_CONFIRM_BROADCAST -> {
                handleAdminBroadcastInput(user, chatId, text, language)
                true
            }
            ConversationState.ADMIN_AWAITING_USER_STATUS -> {
                handleAdminUserStatusInput(user, chatId, text, language)
                true
            }
            ConversationState.ADMIN_AWAITING_GRANT_PREMIUM -> {
                handleAdminGrantInput(user, chatId, text, language)
                true
            }
            else -> false
        }
    }

    private suspend fun activateAdminState(
        userId: Long,
        conversationState: ConversationState,
        adminState: AdminConversationState
    ) {
        adminConversationStateService.set(userId, adminState)
        userService.updateConversationState(userId, conversationState)
    }

    private suspend fun clearAdminState(userId: Long) {
        adminConversationStateService.clear(userId)
        val profile = userService.findUser(userId)
        if (isAdminConversationState(profile?.conversationState)) {
            userService.updateConversationState(userId, null)
        }
    }

    private fun isAdminConversationState(state: ConversationState?): Boolean {
        return when (state) {
            ConversationState.ADMIN_AWAITING_BROADCAST_TEXT,
            ConversationState.ADMIN_CONFIRM_BROADCAST,
            ConversationState.ADMIN_AWAITING_USER_STATUS,
            ConversationState.ADMIN_AWAITING_GRANT_PREMIUM -> true
            else -> false
        }
    }

    private fun isAdmin(userId: Long): Boolean = adminIds.contains(userId)

    private suspend fun handleContentMessage(user: UserProfile, chatId: Long, text: String, language: String) {
        val userId = user.telegramId
        val activeMode = user.mode ?: userService.getMode(userId)
        if (activeMode == null) {
            showMainMenu(user, chatId, language, includeImage = false)
            return
        }
        user.mode = activeMode
        if (activeMode == ConversationMode.HELP) {
            telegramService.safeSendMessage(chatId, helpMessage(language))
            userService.updateMode(userId, null)
            user.mode = null
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

        val promptBundle = Prompts.bundle(activeMode, language)
        val systemPrompt = promptBundle.system
        val stylePrefix = promptBundle.stylePrefix
        val messages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            history.forEach { stored ->
                val content = if (stored.role == ROLE_USER) {
                    "$stylePrefix${stored.content}"
                } else {
                    stored.content
                }
                add(ChatMessage(role = stored.role, content = content))
            }
            add(ChatMessage(role = ROLE_USER, content = "$stylePrefix$text"))
        }
        val completion = openAIClient.complete(messages)
        val intro = i18n.translate(language, "chef_intro")
        val body = completion?.trim().orEmpty()
        val responseText = if (body.isNotEmpty()) {
            "$intro\n$body"
        } else {
            i18n.translate(language, "ai_error")
        }
        telegramService.safeSendMessage(chatId, responseText)
        val assistantMessage = if (body.isNotEmpty()) body else responseText
        messageHistoryService.append(userId, ROLE_ASSISTANT, assistantMessage)
    }

    private suspend fun sendText(
        chatId: Long,
        language: String,
        key: String,
        variables: Map<String, String> = emptyMap()
    ) {
        val text = i18n.translate(language, key, variables)
        telegramService.safeSendMessage(chatId, text)
    }

    private fun helpMessage(language: String): String = i18n.translate(language, "help.body", helpVariables())

    private fun helpVariables(): Map<String, String> = mapOf(
        "website" to helpConfig.websiteUrl,
        "privacy" to helpConfig.privacyPolicyUrl,
        "offer" to helpConfig.publicOfferUrl,
        "support_email" to helpConfig.supportEmail
    ).mapValues { (_, value) -> value.ifBlank { "-" } }

}
