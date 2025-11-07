package app

import java.math.BigDecimal
import java.time.Duration

private const val DEFAULT_PORT = 8080
private const val DEFAULT_FREE_LIMIT = 10
private const val DEFAULT_PREMIUM_DURATION_DAYS = 30L
private const val DEFAULT_PREMIUM_PRICE = 6.99
private const val DEFAULT_LOG_RETENTION_DAYS = 14L
private const val REMINDER_FALLBACK = "2,1"

data class DatabaseConfig(
    val driver: String,
    val url: String,
    val maximumPoolSize: Int = 5,
    val idleTimeout: Duration = Duration.ofMinutes(10)
)

enum class TelegramParseMode {
    NONE,
    MARKDOWNV2,
    HTML;

    companion object {
        fun fromEnv(raw: String?): TelegramParseMode {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
            return values().firstOrNull { it.name == normalized } ?: NONE
        }
    }
}

data class TelegramConfig(
    val botToken: String,
    val webhookUrl: String,
    val secretToken: String,
    val adminIds: Set<Long>,
    val parseMode: TelegramParseMode
)

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

object Env {
    fun load(): AppConfig {
        val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
        val adminIds = System.getenv("ADMIN_IDS")
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

        val freeLimit = System.getenv("FREE_TOTAL_MSG_LIMIT")?.toIntOrNull() ?: DEFAULT_FREE_LIMIT
        val premiumPrice = System.getenv("PREMIUM_PRICE_EUR")?.toBigDecimalOrNull() ?: DEFAULT_PREMIUM_PRICE.toBigDecimal()
        val premiumDuration = System.getenv("PREMIUM_DURATION_DAYS")?.toLongOrNull() ?: DEFAULT_PREMIUM_DURATION_DAYS
        val reminderRaw = System.getenv("REMINDER_DAYS_BEFORE")?.takeIf { it.isNotBlank() } ?: REMINDER_FALLBACK
        val reminderDays = reminderRaw.split(",").mapNotNull { it.trim().toLongOrNull() }.sortedDescending()

        val telegram = TelegramConfig(
            botToken = System.getenv("TELEGRAM_BOT_TOKEN").orEmpty(),
            webhookUrl = System.getenv("TELEGRAM_WEBHOOK_URL").orEmpty(),
            secretToken = System.getenv("TELEGRAM_SECRET_TOKEN").orEmpty(),
            adminIds = adminIds,
            parseMode = TelegramParseMode.fromEnv(System.getenv("PARSE_MODE"))
        )

        val openAI = OpenAIConfig(
            apiKey = System.getenv("OPENAI_API_KEY").orEmpty(),
            model = System.getenv("OPENAI_MODEL").orEmpty(),
            organization = System.getenv("OPENAI_ORG")?.takeIf { it.isNotBlank() },
            project = System.getenv("OPENAI_PROJECT")?.takeIf { it.isNotBlank() }
        )

        val database = DatabaseConfig(
            driver = System.getenv("DB_DRIVER") ?: "org.sqlite.JDBC",
            url = System.getenv("DB_URL") ?: "jdbc:sqlite:/data/chatbotchefeu.db"
        )

        val billing = BillingConfig(
            freeTotalLimit = freeLimit,
            premiumPrice = premiumPrice,
            premiumDurationDays = premiumDuration,
            reminderDays = reminderDays
        )

        val logRetention = System.getenv("LOG_RETENTION_DAYS")?.toLongOrNull() ?: DEFAULT_LOG_RETENTION_DAYS

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
