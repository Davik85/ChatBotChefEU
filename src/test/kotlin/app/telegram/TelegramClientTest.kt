package app.telegram

import app.MessageEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TelegramClientTest {

    @Test
    fun `build params with HTML parse mode`() {
        val message = OutMessage(
            chatId = 123L,
            text = "<b>test</b>",
            parseMode = ParseMode.HTML
        )

        val params = buildSendMessageParams(message)

        assertEquals(123L, params["chat_id"])
        assertEquals("<b>test</b>", params["text"])
        assertEquals("HTML", params["parse_mode"])
        assertFalse(params.containsKey("entities"))
    }

    @Test
    fun `build params with Markdown parse mode`() {
        val message = OutMessage(
            chatId = 456L,
            text = "Hello _World_!",
            parseMode = ParseMode.MARKDOWN
        )

        val params = buildSendMessageParams(message)

        assertEquals("Hello _World_!", params["text"])
        assertEquals("Markdown", params["parse_mode"])
        assertFalse(params.containsKey("entities"))
    }

    @Test
    fun `build params without parse mode omits key`() {
        val message = OutMessage(
            chatId = 789L,
            text = "plain"
        )

        val params = buildSendMessageParams(message)

        assertEquals("plain", params["text"])
        assertFalse(params.containsKey("parse_mode"))
    }

    @Test
    fun `build params with entities drops parse mode`() {
        val message = OutMessage(
            chatId = 111L,
            text = "with entities",
            parseMode = ParseMode.HTML,
            entities = listOf(MessageEntity(offset = 0, length = 4, type = "bold"))
        )

        val params = buildSendMessageParams(message)

        assertNull(params["parse_mode"])
        val entities = params["entities"]
        assertNotNull(entities)
        @Suppress("UNCHECKED_CAST")
        val list = entities as List<MessageEntity>
        assertEquals(1, list.size)
        assertEquals("bold", list.first().type)
    }
}
