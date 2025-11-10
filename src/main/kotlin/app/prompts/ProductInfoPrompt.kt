package app.prompts

import app.LanguageSupport

object ProductInfoPrompt {
    private val systemTemplate: String by lazy { PromptLoader.load("ingredient.prompt.md") }
    private val styleTemplate: String by lazy { PromptLoader.load("ingredient.style.txt") }

    fun system(locale: String): String = replaceLanguagePlaceholder(systemTemplate, locale)

    fun stylePrefix(locale: String): String = replaceLanguagePlaceholder(styleTemplate, locale)

    private fun replaceLanguagePlaceholder(template: String, locale: String): String {
        val lang = locale.take(2).lowercase()
        val langName = LanguageSupport.nativeName(lang)
        return template.replace("{{LANG_NAME}}", langName)
    }
}
