package app

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
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("app.Application")

fun main() {
    val config = Env.load()
    DatabaseFactory.init(config.database)
    val mapper = configuredMapper()
    val i18n = I18n.load(mapper)
    val client = OkHttpClient.Builder().build()
    val telegramService = TelegramService(config.telegram, mapper, client)
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

    embeddedServer(Netty, port = config.port) {
        module(config, mapper, updateProcessor, deduplicationService, reminderService)
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
    install(CallLogging) {
        mdc("component") { "ktor" }
    }
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

    routing {
        get("/health") {
            call.respondText("OK")
        }
        post("/telegram/webhook") {
            val secret = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
            val expectedSecret = appConfig.telegram.secretToken
            if (expectedSecret.isNotBlank() && secret != expectedSecret) {
                logger.warn("Invalid secret token received")
                call.respondText("Unauthorized", status = io.ktor.http.HttpStatusCode.Forbidden)
                return@post
            }
            val payload = call.receiveText()
            val update = runCatching { mapper.readValue(payload, Update::class.java) }
                .onFailure { logger.warn("Failed to parse update: {}", it.message) }
                .getOrNull()
            call.respond(io.ktor.http.HttpStatusCode.OK)
            if (update == null) return@post
            processingScope.launch {
                val processed = deduplicationService.markProcessed(update.updateId)
                if (!processed) {
                    logger.debug("Duplicate update {} ignored", update.updateId)
                    return@launch
                }
                runCatching { updateProcessor.handle(update) }
                    .onFailure { logger.error("Failed to handle update {}", update.updateId, it) }
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

private fun configuredMapper(): ObjectMapper {
    return ObjectMapper().apply {
        findAndRegisterModules()
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
