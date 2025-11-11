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

    @Test
    fun `detects language by native name`() {
        val detected = detectLanguageByName("Português")
        assertEquals("pt", detected)
    }

    @Test
    fun `detects language by code`() {
        val detected = detectLanguageByName("uk")
        assertEquals("uk", detected)
    }

    @Test
    fun `returns null when language name is unsupported`() {
        val detected = detectLanguageByName("Klingon")
        assertNull(detected)
    }
}
