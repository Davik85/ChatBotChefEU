package app

import app.EnvMetadata
import app.BotTransport
import app.TelegramConfig
import app.TelegramParseMode
import app.db.DatabaseFactory
import app.openai.OpenAIClient
import app.services.AdminService
import app.services.DeduplicationService
import app.services.MessageHistoryService
import app.services.PremiumService
import app.services.ReminderService
import app.services.TelegramService
import app.services.UpdateProcessor
import app.services.UsageService
import app.services.UserService
import app.telegram.TelegramClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookSmokeTest {
    private lateinit var mapper: ObjectMapper
    private lateinit var appConfig: AppConfig
    private lateinit var mockServer: MockWebServer

    @BeforeTest
    fun init() {
        mapper = ObjectMapper().findAndRegisterModules()
        appConfig = AppConfig(
            port = 8080,
            telegram = TelegramConfig(
                botToken = "test-token",
                webhookUrl = "https://example.com",
                secretToken = "secret",
                adminIds = emptySet(),
                parseMode = TelegramParseMode.NONE,
                transport = BotTransport.WEBHOOK,
                pollIntervalMs = 800L,
                pollTimeoutSec = 40,
                telegramOffsetFile = "./.run/test_offset.dat"
            ),
            openAI = OpenAIConfig(
                apiKey = "",
                model = "",
                organization = null,
                project = null
            ),
            database = DatabaseConfig(
                driver = "org.sqlite.JDBC",
                url = "jdbc:sqlite:file:webhook?mode=memory&cache=shared"
            ),
            billing = BillingConfig(
                freeTotalLimit = 5,
                premiumPrice = "4.99".toBigDecimal(),
                premiumDurationDays = 30,
                reminderDays = listOf(2, 1)
            ),
            logRetentionDays = 7,
            environment = "PROD"
        )
        DatabaseFactory.init(appConfig.database)
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterTest
    fun cleanup() {
        mockServer.shutdown()
    }

    @Test
    fun `health and webhook respond`() = testApplication {
        val telegramClient = TelegramClient(
            config = appConfig.telegram,
            mapper = mapper,
            client = OkHttpClient(),
            baseUrl = mockServer.url("/").toString().trimEnd('/')
        )
        val telegramService = TelegramService(appConfig.telegram, telegramClient)
        val i18n = I18n.load(mapper)
        val premiumService = PremiumService(appConfig.billing)
        val usageService = UsageService()
        val userService = UserService()
        val messageHistoryService = MessageHistoryService()
        val adminService = AdminService()
        val openAIClient = OpenAIClient(appConfig.openAI, mapper, OkHttpClient())
        val updateProcessor = UpdateProcessor(
            i18n = i18n,
            userService = userService,
            premiumService = premiumService,
            usageService = usageService,
            messageHistoryService = messageHistoryService,
            telegramService = telegramService,
            openAIClient = openAIClient,
            billingConfig = appConfig.billing,
            adminService = adminService,
            adminIds = emptySet()
        )
        val dedup = DeduplicationService()
        val reminder = ReminderService(appConfig.billing, premiumService, userService, telegramService, i18n)

        application {
            module(appConfig, EnvMetadata(emptyMap(), emptyMap()), mapper, updateProcessor, dedup, reminder)
        }

        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("OK", health.bodyAsText())

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))

        val updateJson = """{"update_id":1,"message":{"message_id":10,"chat":{"id":1,"type":"private"},"date":0,"text":"hello","from":{"id":1,"is_bot":false}}}"""
        val response = client.post("/telegram/webhook") {
            header("X-Telegram-Bot-Api-Secret-Token", "secret")
            contentType(ContentType.Application.Json)
            setBody(updateJson)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
