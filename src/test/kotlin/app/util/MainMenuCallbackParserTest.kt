package app.util

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainMenuCallbackParserTest {
    @Test
    fun `parses recipes action`() {
        val action = parseMainMenuCallbackData("mode:recipes")
        assertTrue(action is MainMenuAction.Recipes)
    }

    @Test
    fun `parses calorie action`() {
        val action = parseMainMenuCallbackData("mode:calorie")
        assertTrue(action is MainMenuAction.Calorie)
    }

    @Test
    fun `parses ingredient action`() {
        val action = parseMainMenuCallbackData("mode:ingredient")
        assertTrue(action is MainMenuAction.Ingredient)
    }

    @Test
    fun `parses help action`() {
        val action = parseMainMenuCallbackData("mode:help")
        assertTrue(action is MainMenuAction.Help)
    }

    @Test
    fun `returns null for unknown payload`() {
        val action = parseMainMenuCallbackData("lang:set:en")
        assertNull(action)
    }
}
