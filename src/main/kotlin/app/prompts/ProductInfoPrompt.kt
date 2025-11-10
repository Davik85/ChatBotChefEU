package app.prompts

import app.LanguageSupport

/**
 * System prompt for the "Ingredient Macros" role.
 * Short kcal & BJU per 100 g; note cooking variants; plain chat output.
 */
object ProductInfoPrompt {

    private val template: String by lazy { PromptLoader.load("ingredient.prompt.md") }

    fun system(locale: String): String {
        val langName = LanguageSupport.nativeName(locale.take(2).lowercase())
        return template.replace("{{LANG_NAME}}", langName)
    }
}
