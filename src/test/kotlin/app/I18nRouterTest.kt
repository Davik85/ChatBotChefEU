package app

import kotlin.test.Test
import kotlin.test.assertEquals

class I18nRouterTest {
    @Test
    fun `should replace placeholders`() {
        val translations = mapOf(
            "en" to mapOf(
                "greeting" to "Hello {name}"
            )
        )
        val i18n = I18n(translations)
        val result = i18n.translate("en", "greeting", mapOf("name" to "Chef"))
        assertEquals("Hello Chef", result)
    }
}
