package app.telegram

object MarkdownV2Escaper {
    private val specialChars = Regex("([_\\*\\[\\]\\(\\)~`>#\\+\\-=\\|\\{\\}\\.\\!])")

    fun escape(text: String): String = text.replace(specialChars, "\\\\$1")
}
