package app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageCallbackParserTest {
    @Test
    fun `parses set locale action`() {
        val action = parseLanguageCallbackData("lang:set:de")
        assertTrue(action is LanguageCallbackAction.SetLocale)
        action as LanguageCallbackAction.SetLocale
        assertEquals("de", action.locale)
    }

    @Test
    fun `parses other action`() {
        val action = parseLanguageCallbackData("lang:other")
        assertTrue(action is LanguageCallbackAction.RequestOther)
    }

    @Test
    fun `returns null for unrelated callbacks`() {
        val action = parseLanguageCallbackData("ignored:data")
        assertNull(action)
    }
}
