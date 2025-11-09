package app.telegram

import app.BotTransport
import app.TelegramConfig
import app.TelegramParseMode
import app.Update
import app.services.UpdateHandler
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.nio.file.Files
import org.slf4j.LoggerFactory

class LongPollingRunnerTest {

    @Test
    fun `writes update offset to configured file`() = runBlocking {
        val tempDir = Files.createTempDirectory("offset-test")
        val offsetPath = tempDir.resolve("offset.dat")
        val config = TelegramConfig(
            botToken = "token",
            webhookUrl = "",
            secretToken = "",
            adminIds = emptySet(),
            parseMode = TelegramParseMode.NONE,
            transport = BotTransport.LONG_POLLING,
            pollIntervalMs = 10,
            pollTimeoutSec = 1,
            telegramOffsetFile = offsetPath.toString()
        )
        val updates = listOf(Update(updateId = 42L))
        val telegramClient = mock<TelegramClient> {
            on { getUpdates(anyOrNull(), any()) } doReturn updates doReturn emptyList()
        }
        val handled = mutableListOf<Long>()
        val handler = object : UpdateHandler {
            override suspend fun handle(update: Update) {
                handled += update.updateId
            }
        }
        val runner = LongPollingRunner(
            tg = telegramClient,
            handler = handler,
            config = config,
            logger = LoggerFactory.getLogger("LongPollingRunnerTest")
        )

        val job = launch { runner.run() }
        try {
            withTimeout(1_000) {
                while (handled.isEmpty()) {
                    delay(20)
                }
            }
        } finally {
            job.cancelAndJoin()
        }

        assertTrue(handled.contains(42L))
        assertTrue(offsetPath.toFile().exists())
        assertEquals("42", offsetPath.toFile().readText().trim())

        Files.deleteIfExists(offsetPath)
        Files.deleteIfExists(tempDir)
    }
}
