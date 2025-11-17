package app.services

import app.BotTransport
import app.I18n
import app.TelegramConfig
import app.TelegramParseMode
import app.telegram.TelegramClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.kotlin.mock

class TelegramServiceTest {
    private val config = TelegramConfig(
        botToken = "token",
        webhookUrl = "",
        secretToken = "",
        adminIds = emptySet(),
        parseMode = TelegramParseMode.NONE,
        transport = BotTransport.LONG_POLLING,
        pollIntervalMs = 1000L,
        pollTimeoutSec = 30,
        telegramOffsetFile = "offset",
        welcomeImageUrl = null
    )
    private val telegramService = TelegramService(
        config = config,
        telegramClient = mock<TelegramClient>(),
        i18n = I18n(mapOf("en" to emptyMap()))
    )

    @Test
    fun `split short text into single chunk`() {
        val text = "hello world"
        val parts = telegramService.splitForTelegram(text)
        assertEquals(1, parts.size)
        assertEquals(text, parts.first())
    }

    @Test
    fun `split long text keeps chunks under limit`() {
        val paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
        val text = buildString {
            repeat(200) { append(paragraph).append('\n') }
        }
        val parts = telegramService.splitForTelegram(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 4_096 })
        assertEquals(text.replace("\r\n", "\n"), parts.joinToString(separator = ""))
    }

    @Test
    fun `split respects smaller custom limit`() {
        val text = (1..500).joinToString(separator = " ") { it.toString() }
        val parts = telegramService.splitForTelegram(text, maxLen = 500)
        assertTrue(parts.all { it.length <= 500 })
        assertEquals(text.replace("\r\n", "\n"), parts.joinToString(separator = ""))
    }
}
