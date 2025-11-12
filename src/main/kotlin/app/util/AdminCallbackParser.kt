package app.util

sealed class AdminCallbackAction {
    object Stats : AdminCallbackAction()
    object Broadcast : AdminCallbackAction()
    object BroadcastSend : AdminCallbackAction()
    object Cancel : AdminCallbackAction()
    object UserStatus : AdminCallbackAction()
    object GrantPremium : AdminCallbackAction()
    object LanguageStats : AdminCallbackAction()
}

fun parseAdminCallbackData(raw: String?): AdminCallbackAction? {
    if (raw.isNullOrBlank()) return null
    if (!raw.startsWith("admin:")) return null
    return when (raw.removePrefix("admin:")) {
        "stats" -> AdminCallbackAction.Stats
        "broadcast" -> AdminCallbackAction.Broadcast
        "broadcast_send" -> AdminCallbackAction.BroadcastSend
        "cancel" -> AdminCallbackAction.Cancel
        "user_status" -> AdminCallbackAction.UserStatus
        "grant_premium" -> AdminCallbackAction.GrantPremium
        "lang_stats" -> AdminCallbackAction.LanguageStats
        else -> null
    }
}
