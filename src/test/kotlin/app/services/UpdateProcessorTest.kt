package app.services

import app.BillingConfig
import app.Chat
import app.HelpConfig
import app.I18n
import app.InlineKeyboardMarkup
import app.Message
import app.TelegramUser
import app.Update
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UpdateProcessorTest {

    private val billingConfig = BillingConfig(
        freeTotalLimit = 5,
        premiumPrice = BigDecimal.ONE,
        premiumDurationDays = 30,
        reminderDays = emptyList()
    )
    private val helpConfig = HelpConfig(
        websiteUrl = "",
        privacyPolicyUrl = "",
        publicOfferUrl = "",
        supportEmail = ""
    )
    private val i18n = I18n(mapOf("en" to emptyMap()))

    @Test
    fun `admin state resets on start command`() = runBlocking {
        val telegramService = mock<TelegramService>()
        val userService = mock<UserService>()
        val premiumService = mock<PremiumService>()
        val usageService = mock<UsageService>()
        val messageHistoryService = mock<MessageHistoryService>()
        val openAIClient = mock<app.openai.OpenAIClient>()
        val adminService = mock<AdminService>()
        val broadcastService = mock<BroadcastService>()
        val adminStateService = AdminConversationStateService()

        whenever(telegramService.sendWelcomeImage(any())).thenReturn(101L)
        whenever(telegramService.safeSendMessage(any(), any(), anyOrNull())).thenReturn(201L)
        whenever(telegramService.mainMenuKeyboard(any())).thenReturn(InlineKeyboardMarkup(emptyList()))
        whenever(telegramService.languageMenu(any())).thenReturn(InlineKeyboardMarkup(emptyList()))

        val adminUser = UserProfile(
            telegramId = 1L,
            locale = "en",
            conversationState = ConversationState.ADMIN_AWAITING_GRANT_PREMIUM,
            createdAt = Instant.now(),
            mode = null,
            lastMenuMessageId = null,
            lastWelcomeImageMessageId = null,
            lastWelcomeGreetingMessageId = null,
            lastStartCommandMessageId = null
        )

        adminStateService.set(1L, AdminConversationState.AwaitingGrantPremium)
        whenever(userService.ensureUser(1L, "en"))
            .thenReturn(adminUser)
        whenever(userService.findUser(1L)).thenReturn(adminUser)

        val processor = UpdateProcessor(
            i18n = i18n,
            userService = userService,
            premiumService = premiumService,
            usageService = usageService,
            messageHistoryService = messageHistoryService,
            telegramService = telegramService,
            openAIClient = openAIClient,
            billingConfig = billingConfig,
            adminService = adminService,
            adminConversationStateService = adminStateService,
            broadcastService = broadcastService,
            adminIds = setOf(1L),
            helpConfig = helpConfig
        )

        val update = Update(
            updateId = 10L,
            message = Message(
                message_id = 20,
                from = TelegramUser(id = 1L, isBot = false, language_code = "en"),
                chat = Chat(id = 1L, type = "private"),
                date = 0,
                text = "/start"
            )
        )

        processor.handle(update)

        assertNull(adminStateService.get(1L))
        verify(telegramService, times(1)).sendWelcomeImage(1L)
        verify(telegramService, atLeast(2)).safeSendMessage(any(), any(), anyOrNull())
        verify(telegramService, times(1)).mainMenuKeyboard(any())
    }

    @Test
    fun `start without locale shows language menu first`() = runBlocking {
        val telegramService = mock<TelegramService>()
        val userService = mock<UserService>()
        val premiumService = mock<PremiumService>()
        val usageService = mock<UsageService>()
        val messageHistoryService = mock<MessageHistoryService>()
        val openAIClient = mock<app.openai.OpenAIClient>()
        val adminService = mock<AdminService>()
        val broadcastService = mock<BroadcastService>()
        val adminStateService = AdminConversationStateService()

        whenever(telegramService.safeSendMessage(any(), any(), anyOrNull())).thenReturn(301L)
        whenever(telegramService.mainMenuKeyboard(any())).thenReturn(InlineKeyboardMarkup(emptyList()))
        whenever(telegramService.languageMenu(any())).thenReturn(InlineKeyboardMarkup(emptyList()))

        val user = UserProfile(
            telegramId = 2L,
            locale = null,
            conversationState = null,
            createdAt = Instant.now(),
            mode = null,
            lastMenuMessageId = null,
            lastWelcomeImageMessageId = null,
            lastWelcomeGreetingMessageId = null,
            lastStartCommandMessageId = null
        )

        whenever(userService.ensureUser(2L, "en"))
            .thenReturn(user)
        whenever(userService.findUser(2L)).thenReturn(user)

        val processor = UpdateProcessor(
            i18n = i18n,
            userService = userService,
            premiumService = premiumService,
            usageService = usageService,
            messageHistoryService = messageHistoryService,
            telegramService = telegramService,
            openAIClient = openAIClient,
            billingConfig = billingConfig,
            adminService = adminService,
            adminConversationStateService = adminStateService,
            broadcastService = broadcastService,
            adminIds = emptySet(),
            helpConfig = helpConfig
        )

        val update = Update(
            updateId = 11L,
            message = Message(
                message_id = 21,
                from = TelegramUser(id = 2L, isBot = false, language_code = "en"),
                chat = Chat(id = 2L, type = "private"),
                date = 0,
                text = "/start"
            )
        )

        processor.handle(update)

        verify(telegramService, never()).sendWelcomeImage(any())
        verify(telegramService, times(1)).safeSendMessage(any(), any(), anyOrNull())
    }

    @Test
    fun `language selection sends welcome sequence`() = runBlocking {
        val telegramService = mock<TelegramService>()
        val userService = mock<UserService>()
        val premiumService = mock<PremiumService>()
        val usageService = mock<UsageService>()
        val messageHistoryService = mock<MessageHistoryService>()
        val openAIClient = mock<app.openai.OpenAIClient>()
        val adminService = mock<AdminService>()
        val broadcastService = mock<BroadcastService>()
        val adminStateService = AdminConversationStateService()

        whenever(telegramService.sendWelcomeImage(any())).thenReturn(401L)
        whenever(telegramService.safeSendMessage(any(), any(), anyOrNull())).thenReturn(402L)
        whenever(telegramService.mainMenuKeyboard(any())).thenReturn(InlineKeyboardMarkup(emptyList()))
        whenever(telegramService.languageMenu(any())).thenReturn(InlineKeyboardMarkup(emptyList()))

        val user = UserProfile(
            telegramId = 3L,
            locale = null,
            conversationState = ConversationState.AWAITING_LANGUAGE_SELECTION,
            createdAt = Instant.now(),
            mode = null,
            lastMenuMessageId = null,
            lastWelcomeImageMessageId = null,
            lastWelcomeGreetingMessageId = null,
            lastStartCommandMessageId = null
        )

        whenever(userService.ensureUser(3L, null))
            .thenReturn(user)
        whenever(userService.findUser(3L)).thenReturn(user)

        val processor = UpdateProcessor(
            i18n = i18n,
            userService = userService,
            premiumService = premiumService,
            usageService = usageService,
            messageHistoryService = messageHistoryService,
            telegramService = telegramService,
            openAIClient = openAIClient,
            billingConfig = billingConfig,
            adminService = adminService,
            adminConversationStateService = adminStateService,
            broadcastService = broadcastService,
            adminIds = emptySet(),
            helpConfig = helpConfig
        )

        val callback = Update(
            updateId = 12L,
            callbackQuery = app.CallbackQuery(
                id = "cb-1",
                from = TelegramUser(id = 3L, isBot = false),
                message = Message(
                    message_id = 30,
                    from = null,
                    chat = Chat(id = 3L, type = "private"),
                    date = 0
                ),
                data = "lang:set:en"
            )
        )

        processor.handle(callback)

        verify(userService).updateLocale(3L, "en")
        verify(telegramService, times(1)).sendWelcomeImage(3L)
        verify(telegramService, atLeast(3)).safeSendMessage(any(), any(), anyOrNull())
    }

    @Test
    fun `start for new localized user still prompts for language`() = runBlocking {
        val telegramService = mock<TelegramService>()
        val userService = mock<UserService>()
        val premiumService = mock<PremiumService>()
        val usageService = mock<UsageService>()
        val messageHistoryService = mock<MessageHistoryService>()
        val openAIClient = mock<app.openai.OpenAIClient>()
        val adminService = mock<AdminService>()
        val broadcastService = mock<BroadcastService>()
        val adminStateService = AdminConversationStateService()

        whenever(telegramService.safeSendMessage(any(), any(), anyOrNull())).thenReturn(601L)
        whenever(telegramService.mainMenuKeyboard(any())).thenReturn(InlineKeyboardMarkup(emptyList()))
        whenever(telegramService.languageMenu(any())).thenReturn(InlineKeyboardMarkup(emptyList()))

        val newUser = UserProfile(
            telegramId = 5L,
            locale = "en",
            conversationState = ConversationState.AWAITING_LANGUAGE_SELECTION,
            createdAt = Instant.now(),
            mode = null,
            lastMenuMessageId = null,
            lastWelcomeImageMessageId = null,
            lastWelcomeGreetingMessageId = null,
            lastStartCommandMessageId = null
        )

        whenever(userService.ensureUser(5L, "en"))
            .thenReturn(newUser)
        whenever(userService.findUser(5L)).thenReturn(newUser)

        val processor = UpdateProcessor(
            i18n = i18n,
            userService = userService,
            premiumService = premiumService,
            usageService = usageService,
            messageHistoryService = messageHistoryService,
            telegramService = telegramService,
            openAIClient = openAIClient,
            billingConfig = billingConfig,
            adminService = adminService,
            adminConversationStateService = adminStateService,
            broadcastService = broadcastService,
            adminIds = emptySet(),
            helpConfig = helpConfig
        )

        val update = Update(
            updateId = 14L,
            message = Message(
                message_id = 40,
                from = TelegramUser(id = 5L, isBot = false, language_code = "en"),
                chat = Chat(id = 5L, type = "private"),
                date = 0,
                text = "/start"
            )
        )

        processor.handle(update)

        verify(telegramService, never()).sendWelcomeImage(any())
        verify(telegramService, times(1)).safeSendMessage(any(), any(), anyOrNull())
    }

    @Test
    fun `whoami skips usage counter and clears admin state`() = runBlocking {
        val telegramService = mock<TelegramService>()
        val userService = mock<UserService>()
        val premiumService = mock<PremiumService>()
        val usageService = mock<UsageService>()
        val messageHistoryService = mock<MessageHistoryService>()
        val openAIClient = mock<app.openai.OpenAIClient>()
        val adminService = mock<AdminService>()
        val broadcastService = mock<BroadcastService>()
        val adminStateService = AdminConversationStateService()

        whenever(telegramService.safeSendMessage(any(), any(), anyOrNull())).thenReturn(501L)
        whenever(telegramService.mainMenuKeyboard(any())).thenReturn(InlineKeyboardMarkup(emptyList()))
        whenever(telegramService.languageMenu(any())).thenReturn(InlineKeyboardMarkup(emptyList()))

        val adminUser = UserProfile(
            telegramId = 4L,
            locale = "en",
            conversationState = ConversationState.ADMIN_AWAITING_USER_STATUS,
            createdAt = Instant.now(),
            mode = null,
            lastMenuMessageId = null,
            lastWelcomeImageMessageId = null,
            lastWelcomeGreetingMessageId = null,
            lastStartCommandMessageId = null
        )

        adminStateService.set(4L, AdminConversationState.AwaitingUserStatus)
        whenever(userService.ensureUser(4L, "en"))
            .thenReturn(adminUser)
        whenever(userService.findUser(4L)).thenReturn(adminUser)

        val processor = UpdateProcessor(
            i18n = i18n,
            userService = userService,
            premiumService = premiumService,
            usageService = usageService,
            messageHistoryService = messageHistoryService,
            telegramService = telegramService,
            openAIClient = openAIClient,
            billingConfig = billingConfig,
            adminService = adminService,
            adminConversationStateService = adminStateService,
            broadcastService = broadcastService,
            adminIds = setOf(4L),
            helpConfig = helpConfig
        )

        val update = Update(
            updateId = 13L,
            message = Message(
                message_id = 31,
                from = TelegramUser(id = 4L, isBot = false, username = "chef", first_name = "Chef", language_code = "en"),
                chat = Chat(id = 4L, type = "private"),
                date = 0,
                text = "/whoami"
            )
        )

        processor.handle(update)

        assertNull(adminStateService.get(4L))
        verify(usageService, never()).incrementUsage(any())
        verify(telegramService, times(1)).safeSendMessage(any(), any(), anyOrNull())
    }
}

