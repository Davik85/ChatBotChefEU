package app.prompts

import app.services.ConversationMode

/**
 * Facade to access prompts by mode.
 */
object Prompts {
    fun system(mode: ConversationMode, locale: String): String = when (mode) {
        ConversationMode.RECIPES -> RecipePrompt.system(locale)
        ConversationMode.CALORIE -> CalorieCalculatorPrompt.system(locale)
        ConversationMode.INGREDIENT -> ProductInfoPrompt.system(locale)
        ConversationMode.HELP -> HelpPrompt.system(locale)
    }
}
