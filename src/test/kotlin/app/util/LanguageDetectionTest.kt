package app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageDetectionTest {
    @Test
    fun `detects french greeting`() {
        val detected = detectLanguageByGreeting("Salut!")
        assertEquals("fr", detected)
    }

    @Test
    fun `detects spanish greeting with punctuation`() {
        val detected = detectLanguageByGreeting("Hola!!!")
        assertEquals("es", detected)
    }

    @Test
    fun `returns null when greeting is unknown`() {
        val detected = detectLanguageByGreeting("Goodbye")
        assertNull(detected)
    }
}
