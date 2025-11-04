package app.util

object SecretMasker {
    private const val MASK = "***"

    fun mask(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return if (value.length <= 4) MASK else value.take(2) + MASK + value.takeLast(2)
    }
}
