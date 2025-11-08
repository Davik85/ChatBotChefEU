package app

import app.db.DatabaseFactory
import app.openai.OpenAIClient
import app.services.*
import app.telegram.LongPollingRunner
import app.telegram.TelegramClient
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.Application")

fun main() {
    val config = Env.load()
    DatabaseFactory.init(config.database)
    val mapper = configuredMapper()
    val i18n = I18n.load(mapper)
    val client = okhttp3.OkHttpClient.Builder().build()
    val telegramClient = TelegramClient(config.telegram, mapper, client)
    val telegramService = TelegramService(config.telegram, telegramClient)
    val premiumService = PremiumService(config.billing)
    val usageService = UsageService()
    val userService = UserService()
    val messageHistoryService = MessageHistoryService()
    val adminService = AdminService()
    val openAIClient = OpenAIClient(config.openAI, mapper, client)
    val updateProcessor = UpdateProcessor(
        i18n = i18n,
        userService = userService,
        premiumService = premiumService,
        usageService = usageService,
        messageHistoryService = messageHistoryService,
        telegramService = telegramService,
        openAIClient = openAIClient,
        billingConfig = config.billing,
        adminService = adminService,
        adminIds = config.telegram.adminIds
    )
    val deduplicationService = DeduplicationService()
    val reminderService = ReminderService(config.billing, premiumService, userService, telegramService, i18n)

    // Кейс 1: Локальный запуск (long polling) — не стартуем HTTP сервер, а только polling loop
    if (config.telegram.transport == BotTransport.LONG_POLLING) {
        runCatching { telegramClient.deleteWebhook(dropPendingUpdates = false) }
            .onFailure { logger.warn("Failed to delete webhook before starting long polling: {}", it.message) }
        val pollingRunner = LongPollingRunner(
            tg = telegramClient,
            handler = updateProcessor,
            config = config.telegram,
            logger = LoggerFactory.getLogger(LongPollingRunner::class.java)
        )
        runBlocking {
            logger.info("Starting bot in LONG_POLLING mode (no Ktor HTTP server)")
            pollingRunner.run()
        }
        return
    }

    // Кейс 2: Production (webhook) — HTTP сервер + webhook endpoint
    embeddedServer(Netty, port = config.port) {
        module(
            config,
            mapper,
            updateProcessor,
            deduplicationService,
            reminderService
        )
    }.start(wait = true)
}

fun Application.module(
    appConfig: AppConfig,
    mapper: ObjectMapper,
    updateProcessor: UpdateProcessor,
    deduplicationService: DeduplicationService,
    reminderService: ReminderService
) {
    install(DefaultHeaders)
    install(CallLogging) { mdc("component") { "ktor" } }
    install(ContentNegotiation) {
        jackson {
            configure(SerializationFeature.INDENT_OUTPUT, false)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respondText("Internal error")
        }
    }

    val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    environment.monitor.subscribe(ApplicationStopping) {
        processingScope.cancel()
    }

    routing {
        get("/") { call.respondText("ok") }
        get("/health") { call.respondText("OK") }
        get("/diag/echo") {
            call.respond(
                mapOf(
                    "parse_mode" to appConfig.telegram.parseMode.name,
                    "transport" to appConfig.telegram.transport.name,
                    "webhook_url" to appConfig.telegram.webhookUrl,
                    "env_loaded" to (System.getenv("TELEGRAM_BOT_TOKEN")?.isNotBlank() == true)
                )
            )
        }
        if (appConfig.telegram.transport == BotTransport.WEBHOOK) {
            post("/telegram/webhook") {
                val secret = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
                val expectedSecret = appConfig.telegram.secretToken
                if (expectedSecret.isNotBlank() && secret != expectedSecret) {
                    logger.warn("Invalid Telegram secret token received")
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                val payload = call.receiveText()
                val update = runCatching { mapper.readValue(payload, Update::class.java) }
                    .onFailure { logger.warn("Failed to parse update: {}", it.message) }
                    .getOrNull()
                if (update == null) {
                    call.respond(HttpStatusCode.OK)
                    return@post
                }
                processingScope.launch {
                    val processed = deduplicationService.markProcessed(update.updateId)
                    if (!processed) {
                        logger.debug("Duplicate update {} ignored", update.updateId)
                        return@launch
                    }
                    runCatching { updateProcessor.handle(update) }
                        .onFailure { logger.error("Failed to handle update {}", update.updateId, it) }
                }
                call.respond(HttpStatusCode.OK)
            }
        }
        post("/internal/housekeeping/reminders") {
            processingScope.launch {
                reminderService.dispatchRenewalReminders()
            }
            call.respondText("OK")
        }
    }
}

private fun configuredMapper(): ObjectMapper =
    ObjectMapper().apply {
        findAndRegisterModules()
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
