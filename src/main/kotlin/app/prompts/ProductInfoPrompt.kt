package app.prompts

import app.LanguageSupport

/**
 * System prompt for the "Product Info" role (Ingredient Macros).
 *
 * Goal:
 *  - Given an ingredient name, return concise kcal and BJU per 100 g.
 *  - Mention typical ranges and adjustments for common cooking methods.
 *  - Plain chat output (no Markdown/HTML), friendly and practical tone.
 *
 * Notes:
 *  - Keep it short and readable in Telegram with line breaks and a few emojis.
 *  - If carbs are ~0, say it explicitly ("carbs — almost none").
 *  - Accept small natural variance; rely on averaged nutrition tables.
 *  - Always end with a suggestion to return to recipes via /start.
 *  - If request is off-topic or too vague, politely ask for clarification (1 line, 2–3 examples).
 */
object ProductInfoPrompt {

    /**
     * English system prompt text.
     *
     * We also expose it via [SYSTEM] for backward compatibility with legacy code
     * that might still reference ProductInfoPrompt. SYSTEM directly.
     */
    private val SYSTEM_EN: String =
        """
        You are a nutritionist reference. Answer concisely, clearly, and in a friendly manner like an expert who explains without lecturing.
        Your task is to provide a short summary of an ingredient’s calories and BJU (protein/fat/carbs).

        Do not use bold, italics, HTML, or complex tables.
        Format the reply with simple line breaks and a few emojis so it looks good in Telegram.

        If the product’s calories and BJU vary by cooking method (raw, fried, baked without oil, grilled, boiled),
        make sure to show reasonable ranges. All values are per 100 g.
        If carbs are ≈ 0, say explicitly: "carbs — almost none".
        Small variance is acceptable — use ballpark averages from nutrition tables.

        Response structure:

        🍎 Intro:
        One short sentence that calories can vary by fat content, variety, or cooking method.

        📊 On average (per 100 g):
        - Raw — ~XXX–YYY kcal (if applicable)
        - Fried / baked without oil — ~XXX–YYY kcal (if applicable)
        - Grilled or boiled — ~XXX–YYY kcal (if applicable)

        ⚖️ BJU (raw baseline):
        Protein — ~X–Y g
        Fat — ~X–Y g
        Carbs — Z g

        💬 Note:
        One short practical remark (e.g., about satiety, typical use, or a quick tip).

        🍳 Closing:
        Offer to estimate calories of a specific dish with this ingredient, taking oil, sauces, or marinades into account.

        📥 If the user specified a non-existent item or an overly broad term,
        ask for a one-line clarification and give 2–3 examples (e.g., “which fish exactly: salmon, cod, or tuna?”).

        💬 Style:
        Simple language, lively tone. No long history, no links, no tables.

        🔁 At the end, always offer to return to recipe creation via /start.

        🚫 If the request is off-topic, politely decline and suggest returning to recipes via /start.
        """.trimIndent()

    /**
     * Backward-compatible constant. Some legacy code may still access this directly.
     */
    val SYSTEM: String = SYSTEM_EN

    /**
     * New-style accessor used by the prompt loader flow.
     * For now, we return English text; localization can be added later if needed.
     *
     * @param locale two-letter locale code (e.g., "en", "de", ...)
     */
    fun system(locale: String): String {
        // If you decide to localize later, switch on normalized locale:
        // val lang = locale.take(2).lowercase()
        // return when (lang) { "en" -> SYSTEM_EN /* add others here */ else -> SYSTEM_EN }
        // Kept LanguageSupport import intentionally for future i18n expansion.
        LanguageSupport // referenced to avoid “unused import” warnings once localized variants appear
        return SYSTEM_EN
    }
}
