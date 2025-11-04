package app.services

import app.BillingConfig
import app.I18n
import app.SendMessageRequest
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"))

class ReminderService(
    private val billingConfig: BillingConfig,
    private val premiumService: PremiumService,
    private val userService: UserService,
    private val telegramService: TelegramService,
    private val i18n: I18n
) {
    private val logger = LoggerFactory.getLogger(ReminderService::class.java)

    suspend fun dispatchRenewalReminders() {
        val due = premiumService.findExpiringWithin(billingConfig.reminderDays)
        if (due.isEmpty()) {
            logger.debug("No reminders due")
            return
        }
        due.forEach { (telegramId, expiry) ->
            val user = userService.findUser(telegramId) ?: return@forEach
            val language = user.language
            val message = i18n.translate(language, "premium_reminder", mapOf("date" to DATE_FORMATTER.format(expiry)))
            val request = SendMessageRequest(chatId = telegramId, text = message)
            runCatching { telegramService.sendMessage(request) }
                .onFailure { logger.warn("Failed to send reminder to {}: {}", telegramId, it.message) }
        }
    }
}
