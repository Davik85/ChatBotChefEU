package app.prompts

import app.LanguageSupport

object HelpPrompt {

    private val template: String by lazy { PromptLoader.load("help.prompt.md") }

    fun system(locale: String): String {
        val langName = LanguageSupport.nativeName(locale.take(2).lowercase())
        return template.replace("{{LANG_NAME}}", langName)
    }
}
