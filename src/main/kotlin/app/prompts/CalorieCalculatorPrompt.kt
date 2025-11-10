package app.prompts

import app.LanguageSupport

/**
 * System prompt for the "Calorie Calculator" role.
 * Mifflin–St Jeor, activity factors, goal corrections, clean chat output.
 */
object CalorieCalculatorPrompt {

    private val template: String by lazy { PromptLoader.load("calorie.prompt.md") }

    fun system(locale: String): String {
        val langName = LanguageSupport.nativeName(locale.take(2).lowercase())
        return template.replace("{{LANG_NAME}}", langName)
    }
}
