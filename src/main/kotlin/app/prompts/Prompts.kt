package app.prompts

import app.services.ConversationMode

/**
 * Facade to access prompts by mode.
 */
data class PromptBundle(val system: String, val stylePrefix: String)

object Prompts {
    fun bundle(mode: ConversationMode, locale: String): PromptBundle = when (mode) {
        ConversationMode.RECIPES -> PromptBundle(
            system = RecipePrompt.system(locale),
            stylePrefix = RecipePrompt.stylePrefix(locale)
        )
        ConversationMode.CALORIE -> PromptBundle(
            system = CalorieCalculatorPrompt.system(locale),
            stylePrefix = CalorieCalculatorPrompt.stylePrefix(locale)
        )
        ConversationMode.INGREDIENT -> PromptBundle(
            system = ProductInfoPrompt.system(locale),
            stylePrefix = ProductInfoPrompt.stylePrefix(locale)
        )
        ConversationMode.HELP -> PromptBundle(
            system = HelpPrompt.system(locale),
            stylePrefix = HelpPrompt.stylePrefix(locale)
        )
    }

    fun system(mode: ConversationMode, locale: String): String = bundle(mode, locale).system

    fun stylePrefix(mode: ConversationMode, locale: String): String = bundle(mode, locale).stylePrefix
}
