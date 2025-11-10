package app.prompts

/**
 * Facade to access prompts by mode.
 */
object Prompts {
    enum class Mode { RECIPES, CALORIES, INGREDIENT }

    fun system(mode: Mode, locale: String): String = when (mode) {
        Mode.RECIPES   -> RecipePrompt.system(locale)
        Mode.CALORIES  -> CalorieCalculatorPrompt.system(locale)
        Mode.INGREDIENT-> ProductInfoPrompt.system(locale)
    }
}
