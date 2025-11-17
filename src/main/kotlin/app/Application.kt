package app

import app.EnvMetadata
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
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger("app.Application")

fun main() {
    val loadedEnv = Env.load()
    val config = loadedEnv.config

    println("=== START MODE: ${config.telegram.transport} (env via ${loadedEnv.metadata.primarySourceLabel})")
    loadedEnv.metadata.logSources()

    if (config.telegram.botToken.isBlank()) {
        logger.error("Telegram bot token is empty. Shutting down.")
        return
    }

    DatabaseFactory.init(config.database)
    val mapper = configuredMapper()
    val telegramHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .build()
    val openAiHttpClient = telegramHttpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .build()
    val openAIClient = OpenAIClient(config.openAI, mapper, openAiHttpClient)
    val i18n = I18n.load(mapper)
    val telegramClient = TelegramClient(config.telegram, mapper, telegramHttpClient)
    val telegramService = TelegramService(config.telegram, telegramClient, i18n)
    val premiumService = PremiumService(config.billing)
    val usageService = UsageService()
    val userService = UserService()
    val messageHistoryService = MessageHistoryService()
    val adminService = AdminService()
    val adminConversationStateService = AdminConversationStateService()
    val broadcastService = BroadcastService(telegramService)
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
        adminConversationStateService = adminConversationStateService,
        broadcastService = broadcastService,
        adminIds = config.telegram.adminIds,
        helpConfig = config.help
    )
    val deduplicationService = DeduplicationService()
    val reminderService = ReminderService(config.billing, premiumService, userService, telegramService, i18n)

    if (config.telegram.transport == BotTransport.LONG_POLLING) {
        runCatching { telegramClient.deleteWebhook(dropPendingUpdates = false) }
            .onFailure {
                logger.warn("Failed to delete webhook before starting long polling: {}", it.message)
            }
        val pollingRunner = LongPollingRunner(
            tg = telegramClient,
            handler = updateProcessor,
            config = config.telegram,
            logger = LoggerFactory.getLogger(LongPollingRunner::class.java)
        )
        runBlocking {
            logger.info("Running bot in LONG_POLLING mode (no HTTP server is started!)")
            pollingRunner.run()
        }
        return // <--- вот этот return КРИТИЧЕН! Без него сервер продолжит запускаться!
    }

    // Кейс 2: Production (webhook) — HTTP сервер + webhook endpoint
    embeddedServer(Netty, port = config.port) {
        module(
            config,
            loadedEnv.metadata,
            mapper,
            updateProcessor,
            deduplicationService,
            reminderService
        )
    }.start(wait = true)
}

fun Application.module(
    appConfig: AppConfig,
    envMetadata: EnvMetadata,
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
                    "transport" to appConfig.telegram.transport.name,
                    "parse_mode" to appConfig.telegram.parseMode.name,
                    "webhook_url" to appConfig.telegram.webhookUrl,
                    "env_source" to envMetadata.summary.name,
                    "offset_file" to appConfig.telegram.telegramOffsetFile,
                    "db_url" to appConfig.database.url
                )
            )
        }
        if (appConfig.environment.equals("DEV", ignoreCase = true)) {
            get("/diag/vars") {
                call.respond(
                    mapOf(
                        "transport" to appConfig.telegram.transport.name,
                        "parse_mode" to appConfig.telegram.parseMode.name,
                        "poll_interval_ms" to appConfig.telegram.pollIntervalMs,
                        "poll_timeout_sec" to appConfig.telegram.pollTimeoutSec,
                        "webhook_url" to appConfig.telegram.webhookUrl,
                        "env_source" to envMetadata.summary.name,
                        "offset_file" to appConfig.telegram.telegramOffsetFile,
                        "db_url" to appConfig.database.url,
                        "app_env" to appConfig.environment
                    )
                )
            }
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
