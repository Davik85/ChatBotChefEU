package app.services

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration

class BroadcastService(
    private val telegramService: TelegramService,
    private val batchSize: Int = 30,
    private val messageDelay: Duration = Duration.ofMillis(40),
    private val batchDelay: Duration = Duration.ofSeconds(1),
    private val maxRetries: Int = 2,
    private val retryDelay: Duration = Duration.ofMillis(250)
) {
    private val logger = LoggerFactory.getLogger(BroadcastService::class.java)

    data class Result(val total: Int, val delivered: Int, val failed: Int)

    suspend fun dispatch(adminId: Long, targetIds: List<Long>, message: String): Result {
        val distinctTargets = targetIds.distinct()
        var delivered = 0
        var failed = 0
        logger.info(
            "Admin {} started broadcast to {} users",
            adminId,
            distinctTargets.size
        )
        val chunks = distinctTargets.chunked(batchSize)
        chunks.forEachIndexed { index, chunk ->
            chunk.forEach { chatId ->
                var attempt = 0
                var success = false
                while (attempt <= maxRetries && !success) {
                    attempt++
                    val messageId = telegramService.safeSendMessage(chatId, message)
                    if (messageId != null) {
                        delivered++
                        success = true
                    } else if (attempt <= maxRetries) {
                        delay(retryDelay.toMillis())
                    }
                }
                if (!success) {
                    failed++
                    logger.warn(
                        "Broadcast delivery to {} failed after {} attempts",
                        chatId,
                        attempt
                    )
                }
                delay(messageDelay.toMillis())
            }
            if (index < chunks.lastIndex) {
                delay(batchDelay.toMillis())
            }
        }
        logger.info(
            "Admin {} finished broadcast: delivered={} failed={} total={}",
            adminId,
            delivered,
            failed,
            distinctTargets.size
        )
        return Result(
            total = distinctTargets.size,
            delivered = delivered,
            failed = failed
        )
    }
}
