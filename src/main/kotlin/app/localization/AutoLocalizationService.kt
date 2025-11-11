package app.localization

/**
 * Adapter interface that bridges runtime translation providers used by [app.I18n].
 */
fun interface AutoLocalizationService {
    /**
     * Returns the localized version of [sourceText] for the [targetLanguage].
     *
     * Implementations should be side-effect free and rely on caching to avoid
     * hitting external services repeatedly. Returning `null` indicates that the
     * translation could not be produced and the caller should fall back to the
     * source text.
     */
    fun translate(targetLanguage: String, sourceText: String): String?

    companion object
}

object NoopAutoLocalizationService : AutoLocalizationService {
    override fun translate(targetLanguage: String, sourceText: String): String? = null
}
