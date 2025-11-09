package app.telegram

import app.TelegramConfig
import app.Update
import app.services.UpdateHandler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import java.io.File

class LongPollingRunner(
    private val tg: TelegramClient,
    private val handler: UpdateHandler,
    private val config: TelegramConfig,
    private val logger: Logger
) {
    private var lastUpdateId: Long = loadOffset()

    suspend fun run() = coroutineScope {
        logger.info(
            "LongPolling: started with timeout={}s interval={}ms offsetFile={}",
            config.pollTimeoutSec,
            config.pollIntervalMs,
            config.telegramOffsetFile
        )
        while (isActive) {
            try {
                val updates = tg.getUpdates(
                    offset = if (lastUpdateId == 0L) null else lastUpdateId + 1,
                    timeoutSec = config.pollTimeoutSec
                )
                if (updates.isNotEmpty()) {
                    processUpdates(updates)
                }
            } catch (e: Exception) {
                logger.warn("LongPolling: cycle error", e)
            }
            delay(config.pollIntervalMs)
        }
    }

    private suspend fun processUpdates(updates: List<Update>) {
        for (u in updates) {
            try {
                handler.handle(u)
                if (u.updateId > lastUpdateId) {
                    lastUpdateId = u.updateId
                }
            } catch (e: Exception) {
                logger.error("LongPolling: handle error updateId={}", u.updateId, e)
            }
        }
        saveOffset(lastUpdateId)
    }

    private fun loadOffset(): Long = runCatching {
        val file = File(config.telegramOffsetFile)
        if (file.exists()) file.readText().trim().toLongOrNull() ?: 0L else 0L
    }.getOrDefault(0L)

    private fun saveOffset(value: Long) = runCatching {
        val file = File(config.telegramOffsetFile)
        file.parentFile?.mkdirs()
        file.writeText(value.toString())
    }
}
