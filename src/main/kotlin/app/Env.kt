package app

import java.io.File
import java.math.BigDecimal
import java.time.Duration

private const val DEFAULT_PORT = 8080
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
    val logRetentionDays: Long
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

private fun dotEnvValue(key: String): String? = runCatching {
    val instance = dotEnvInstance ?: return null
    val method = dotEnvGetMethod ?: return null
    (method.invoke(instance, key) as String?)?.normalized()
}.getOrNull()

private fun systemValue(key: String): String? = System.getenv(key).normalized()

private val dotEnvTransport: String? by lazy { dotEnvValue("TELEGRAM_TRANSPORT")?.uppercase() }
private val dotEnvAppEnv: String? by lazy { dotEnvValue("APP_ENV")?.uppercase() }
private val dotEnvFileExists: Boolean by lazy { runCatching { File(".env").exists() }.getOrDefault(false) }

private val shouldUseDotEnv: Boolean by lazy {
    val systemTransport = systemValue("TELEGRAM_TRANSPORT")?.uppercase()
    val systemAppEnv = systemValue("APP_ENV")?.uppercase()

    when {
        systemTransport == "LONG_POLLING" -> true
        systemAppEnv == "DEV" -> true
        dotEnvTransport == "LONG_POLLING" -> true
        dotEnvAppEnv == "DEV" -> true
        dotEnvFileExists && !systemAppEnv.equals("PROD", ignoreCase = true) -> dotEnvInstance != null
        else -> false
    }
}

private fun envValue(key: String): String? {
    systemValue(key)?.let { return it }
    if (shouldUseDotEnv) {
        dotEnvValue(key)?.let { return it }
    }
    return null
}

private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

object Env {
    fun load(): AppConfig {
        val port = envValue("PORT")?.toIntOrNull() ?: DEFAULT_PORT
        val adminIds = envValue("ADMIN_IDS")
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

        val freeLimit = envValue("FREE_TOTAL_MSG_LIMIT")?.toIntOrNull() ?: DEFAULT_FREE_LIMIT
        val premiumPrice = envValue("PREMIUM_PRICE_EUR")?.toBigDecimalOrNull() ?: DEFAULT_PREMIUM_PRICE.toBigDecimal()
        val premiumDuration = envValue("PREMIUM_DURATION_DAYS")?.toLongOrNull() ?: DEFAULT_PREMIUM_DURATION_DAYS
        val reminderRaw = envValue("REMINDER_DAYS_BEFORE") ?: REMINDER_FALLBACK
        val reminderDays = reminderRaw.split(",").mapNotNull { it.trim().toLongOrNull() }.sortedDescending()

        val transport = BotTransport.fromEnv(envValue("TELEGRAM_TRANSPORT"))
        val appEnv = envValue("APP_ENV")?.uppercase()
        val useDevDefaults = transport == BotTransport.LONG_POLLING || appEnv == "DEV"
        val pollInterval = envValue("TELEGRAM_POLL_INTERVAL_MS")?.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_MS
        val pollTimeout = envValue("TELEGRAM_POLL_TIMEOUT_SEC")?.toIntOrNull() ?: DEFAULT_POLL_TIMEOUT_SEC
        val offsetFile = envValue("TELEGRAM_OFFSET_FILE")
            ?: if (useDevDefaults) DEFAULT_OFFSET_FILE_DEV else DEFAULT_OFFSET_FILE_PROD

        ensureParentDirectory(offsetFile)

        val telegram = TelegramConfig(
            botToken = envValue("TELEGRAM_BOT_TOKEN").orEmpty(),
            webhookUrl = envValue("TELEGRAM_WEBHOOK_URL").orEmpty(),
            secretToken = envValue("TELEGRAM_SECRET_TOKEN").orEmpty(),
            adminIds = adminIds,
            parseMode = TelegramParseMode.fromEnv(envValue("PARSE_MODE")),
            transport = transport,
            pollIntervalMs = pollInterval,
            pollTimeoutSec = pollTimeout,
            telegramOffsetFile = offsetFile
        )

        val openAI = OpenAIConfig(
            apiKey = envValue("OPENAI_API_KEY").orEmpty(),
            model = envValue("OPENAI_MODEL").orEmpty(),
            organization = envValue("OPENAI_ORG"),
            project = envValue("OPENAI_PROJECT")
        )

        val database = DatabaseConfig(
            driver = envValue("DB_DRIVER") ?: "org.sqlite.JDBC",
            url = envValue("DB_URL") ?: if (useDevDefaults) DEFAULT_DB_URL_DEV else DEFAULT_DB_URL_PROD
        )

        ensureDatabaseDirectory(database.url)

        val billing = BillingConfig(
            freeTotalLimit = freeLimit,
            premiumPrice = premiumPrice,
            premiumDurationDays = premiumDuration,
            reminderDays = reminderDays
        )

        val logRetention = envValue("LOG_RETENTION_DAYS")?.toLongOrNull() ?: DEFAULT_LOG_RETENTION_DAYS

        return AppConfig(
            port = port,
            telegram = telegram,
            openAI = openAI,
            database = database,
            billing = billing,
            logRetentionDays = logRetention
        )
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
