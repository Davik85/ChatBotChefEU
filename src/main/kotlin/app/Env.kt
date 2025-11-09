package app

import java.io.File
import java.math.BigDecimal
import java.time.Duration

private const val DEFAULT_PORT = 8081
private const val DEFAULT_FREE_LIMIT = 10
private const val DEFAULT_PREMIUM_DURATION_DAYS = 30L
private const val DEFAULT_PREMIUM_PRICE = 6.99
private const val DEFAULT_LOG_RETENTION_DAYS = 14L
private const val REMINDER_FALLBACK = "3"
private const val DEFAULT_POLL_INTERVAL_MS = 800L
private const val DEFAULT_POLL_TIMEOUT_SEC = 40
private const val DEFAULT_OFFSET_FILE_PROD = "/var/lib/chatbotchefeu/update_offset.dat"
private const val DEFAULT_OFFSET_FILE_DEV = "./.run/update_offset.dat"
private const val DEFAULT_DB_URL_PROD = "jdbc:sqlite:/data/chatbotchefeu.db"
private const val DEFAULT_DB_URL_DEV = "jdbc:sqlite:./.run/dev.db"

enum class ValueOrigin { SYSTEM, DOTENV, DEFAULT }

enum class EnvSourceSummary { SYSTEM, DOTENV, MIXED }

data class EnvMetadata(
    val values: Map<String, String?>,
    val sources: Map<String, ValueOrigin>
) {
    private val hasSystem = sources.values.any { it == ValueOrigin.SYSTEM }
    private val hasDotEnv = sources.values.any { it == ValueOrigin.DOTENV }

    val summary: EnvSourceSummary = when {
        hasDotEnv && hasSystem -> EnvSourceSummary.MIXED
        hasDotEnv -> EnvSourceSummary.DOTENV
        else -> EnvSourceSummary.SYSTEM
    }

    val primarySourceLabel: String = if (summary == EnvSourceSummary.SYSTEM) {
        EnvSourceSummary.SYSTEM.name
    } else {
        EnvSourceSummary.DOTENV.name
    }

    fun logSources() {
        values.keys.sorted().forEach { key ->
            val origin = sources[key] ?: ValueOrigin.DEFAULT
            val value = values[key]
            println("ENV $key=${maskValue(key, value)} (${origin.name})")
        }
    }
}

data class LoadedEnv(
    val config: AppConfig,
    val metadata: EnvMetadata
)

data class DatabaseConfig(
    val driver: String,
    val url: String,
    val maximumPoolSize: Int = 5,
    val idleTimeout: Duration = Duration.ofMinutes(10)
)

enum class TelegramParseMode {
    NONE,
    MARKDOWN,
    HTML;

    companion object {
        fun fromEnv(raw: String?): TelegramParseMode {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            return when (normalized) {
                "HTML" -> HTML
                "MARKDOWN" -> MARKDOWN
                else -> NONE
            }
        }
    }
}

data class TelegramConfig(
    val botToken: String,
    val webhookUrl: String,
    val secretToken: String,
    val adminIds: Set<Long>,
    val parseMode: TelegramParseMode,
    val transport: BotTransport,
    val pollIntervalMs: Long,
    val pollTimeoutSec: Int,
    val telegramOffsetFile: String
)

enum class BotTransport {
    WEBHOOK,
    LONG_POLLING;

    companion object {
        fun fromEnv(raw: String?): BotTransport {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            return values().firstOrNull { it.name == normalized } ?: WEBHOOK
        }
    }
}

data class OpenAIConfig(
    val apiKey: String,
    val model: String,
    val organization: String?,
    val project: String?
)

data class BillingConfig(
    val freeTotalLimit: Int,
    val premiumPrice: BigDecimal,
    val premiumDurationDays: Long,
    val reminderDays: List<Long>
)

data class AppConfig(
    val port: Int,
    val telegram: TelegramConfig,
    val openAI: OpenAIConfig,
    val database: DatabaseConfig,
    val billing: BillingConfig,
    val logRetentionDays: Long,
    val environment: String
)

private val dotEnvInstance: Any? by lazy {
    runCatching {
        val klass = Class.forName("io.github.cdimascio.dotenv.Dotenv")
        val builder = klass.getMethod("configure").invoke(null)
        builder.javaClass.getMethod("ignoreIfMalformed").invoke(builder)
        builder.javaClass.getMethod("ignoreIfMissing").invoke(builder)
        builder.javaClass.getMethod("load").invoke(builder)
    }.getOrNull()
}

private val dotEnvGetMethod by lazy {
    dotEnvInstance?.javaClass?.getMethod("get", String::class.java)
}

private fun dotEnvValueRaw(key: String): String? = runCatching {
    val instance = dotEnvInstance ?: return null
    val method = dotEnvGetMethod ?: return null
    (method.invoke(instance, key) as String?)?.normalized()
}.getOrNull()

private fun systemValue(key: String): String? = System.getenv(key).normalized()

private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private class EnvTracker(private val useDotEnv: Boolean) {
    private val values = linkedMapOf<String, String?>()
    private val sources = linkedMapOf<String, ValueOrigin>()

    fun get(key: String, defaultProvider: (() -> String?)? = null): String? {
        val system = systemValue(key)
        if (system != null) {
            record(key, system, ValueOrigin.SYSTEM)
            return system
        }
        if (useDotEnv) {
            val dotEnv = dotEnvValueRaw(key)
            if (dotEnv != null) {
                record(key, dotEnv, ValueOrigin.DOTENV)
                return dotEnv
            }
        }
        val defaultValue = defaultProvider?.invoke()
        record(key, defaultValue, ValueOrigin.DEFAULT)
        return defaultValue
    }

    private fun record(key: String, value: String?, origin: ValueOrigin) {
        values[key] = value
        sources[key] = origin
    }

    fun metadata(): EnvMetadata = EnvMetadata(values.toMap(), sources.toMap())
}

object Env {
    fun load(): LoadedEnv {
        val systemTransport = systemValue("TELEGRAM_TRANSPORT")?.uppercase()
        val systemAppEnv = systemValue("APP_ENV")?.uppercase()
        val dotEnvTransport = dotEnvValueRaw("TELEGRAM_TRANSPORT")?.uppercase()
        val dotEnvAppEnv = dotEnvValueRaw("APP_ENV")?.uppercase()

        val allowDotEnv = when {
            systemTransport == "LONG_POLLING" -> true
            systemAppEnv == "DEV" -> true
            dotEnvTransport == "LONG_POLLING" -> true
            dotEnvAppEnv == "DEV" -> true
            else -> false
        }

        val tracker = EnvTracker(allowDotEnv)

        val port = tracker.get("PORT") { DEFAULT_PORT.toString() }?.toIntOrNull() ?: DEFAULT_PORT
        val transport = BotTransport.fromEnv(tracker.get("TELEGRAM_TRANSPORT"))
        val appEnv = tracker.get("APP_ENV") { "PROD" }!!.uppercase()
        val useDevDefaults = transport == BotTransport.LONG_POLLING || appEnv == "DEV"

        val pollIntervalMs = tracker.get("TELEGRAM_POLL_INTERVAL_MS") { DEFAULT_POLL_INTERVAL_MS.toString() }
            ?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_MS
        val pollTimeoutSec = tracker.get("TELEGRAM_POLL_TIMEOUT_SEC") { DEFAULT_POLL_TIMEOUT_SEC.toString() }
            ?.toIntOrNull() ?: DEFAULT_POLL_TIMEOUT_SEC

        val offsetDefault = if (useDevDefaults) DEFAULT_OFFSET_FILE_DEV else DEFAULT_OFFSET_FILE_PROD
        val offsetFile = tracker.get("TELEGRAM_OFFSET_FILE") { offsetDefault } ?: offsetDefault
        ensureParentDirectory(offsetFile)

        val reminderRaw = tracker.get("REMINDER_DAYS_BEFORE") { REMINDER_FALLBACK } ?: REMINDER_FALLBACK
        val reminderDays = reminderRaw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .sortedDescending()

        val adminIds = tracker.get("ADMIN_IDS")
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

        val freeLimit = tracker.get("FREE_TOTAL_MSG_LIMIT") { DEFAULT_FREE_LIMIT.toString() }
            ?.toIntOrNull()
            ?: DEFAULT_FREE_LIMIT

        val premiumPrice = tracker.get("PREMIUM_PRICE_EUR") { DEFAULT_PREMIUM_PRICE.toString() }
            ?.toBigDecimalOrNull()
            ?: DEFAULT_PREMIUM_PRICE.toBigDecimal()

        val premiumDuration = tracker.get("PREMIUM_DURATION_DAYS") { DEFAULT_PREMIUM_DURATION_DAYS.toString() }
            ?.toLongOrNull()
            ?: DEFAULT_PREMIUM_DURATION_DAYS

        val telegram = TelegramConfig(
            botToken = tracker.get("TELEGRAM_BOT_TOKEN").orEmpty(),
            webhookUrl = tracker.get("TELEGRAM_WEBHOOK_URL").orEmpty(),
            secretToken = tracker.get("TELEGRAM_SECRET_TOKEN").orEmpty(),
            adminIds = adminIds,
            parseMode = TelegramParseMode.fromEnv(tracker.get("PARSE_MODE")),
            transport = transport,
            pollIntervalMs = pollIntervalMs,
            pollTimeoutSec = pollTimeoutSec,
            telegramOffsetFile = offsetFile
        )

        val openAI = OpenAIConfig(
            apiKey = tracker.get("OPENAI_API_KEY").orEmpty(),
            model = tracker.get("OPENAI_MODEL").orEmpty(),
            organization = tracker.get("OPENAI_ORG"),
            project = tracker.get("OPENAI_PROJECT")
        )

        val dbDefault = if (useDevDefaults) DEFAULT_DB_URL_DEV else DEFAULT_DB_URL_PROD
        val database = DatabaseConfig(
            driver = tracker.get("DB_DRIVER") { "org.sqlite.JDBC" } ?: "org.sqlite.JDBC",
            url = tracker.get("DB_URL") { dbDefault } ?: dbDefault
        )
        ensureDatabaseDirectory(database.url)

        val billing = BillingConfig(
            freeTotalLimit = freeLimit,
            premiumPrice = premiumPrice,
            premiumDurationDays = premiumDuration,
            reminderDays = reminderDays
        )

        val logRetention = tracker.get("LOG_RETENTION_DAYS") { DEFAULT_LOG_RETENTION_DAYS.toString() }
            ?.toLongOrNull()
            ?: DEFAULT_LOG_RETENTION_DAYS

        val config = AppConfig(
            port = port,
            telegram = telegram,
            openAI = openAI,
            database = database,
            billing = billing,
            logRetentionDays = logRetention,
            environment = appEnv
        )

        val metadata = tracker.metadata()

        return LoadedEnv(config, metadata)
    }
}

private fun ensureParentDirectory(path: String) {
    runCatching {
        val parent = File(path).parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }
    }
}

private fun ensureDatabaseDirectory(jdbcUrl: String) {
    if (!jdbcUrl.startsWith("jdbc:sqlite:")) return
    val dbPath = jdbcUrl.removePrefix("jdbc:sqlite:")
    if (dbPath.equals(":memory:", ignoreCase = true)) return
    runCatching {
        val file = File(dbPath)
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
    }
}

private fun maskValue(key: String, value: String?): String {
    val upperKey = key.uppercase()
    val shouldMask = listOf("TOKEN", "KEY", "SECRET", "PASSWORD").any { upperKey.contains(it) }
    return when {
        value == null -> "<null>"
        value.isEmpty() -> "<empty>"
        shouldMask -> "****"
        else -> value
    }
}
