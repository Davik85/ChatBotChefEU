package app.util

import app.services.AdminBroadcastType

sealed class AdminCallbackAction {
    object Stats : AdminCallbackAction()
    object Broadcast : AdminCallbackAction()
    data class BroadcastType(val type: AdminBroadcastType) : AdminCallbackAction()
    object BroadcastSend : AdminCallbackAction()
    object Cancel : AdminCallbackAction()
    object UserStatus : AdminCallbackAction()
    object GrantPremium : AdminCallbackAction()
    object LanguageStats : AdminCallbackAction()
}

fun parseAdminCallbackData(raw: String?): AdminCallbackAction? {
    if (raw.isNullOrBlank()) return null
    if (!raw.startsWith("admin:")) return null
    val payload = raw.removePrefix("admin:")
    return when (payload) {
        "stats" -> AdminCallbackAction.Stats
        "broadcast" -> AdminCallbackAction.Broadcast
        "broadcast_send" -> AdminCallbackAction.BroadcastSend
        "cancel" -> AdminCallbackAction.Cancel
        "user_status" -> AdminCallbackAction.UserStatus
        "grant_premium" -> AdminCallbackAction.GrantPremium
        "lang_stats" -> AdminCallbackAction.LanguageStats
        else -> parseBroadcastTypeAction(payload)
    }
}

private fun parseBroadcastTypeAction(payload: String): AdminCallbackAction? {
    if (!payload.startsWith("broadcast_type:")) return null
    return when (payload.removePrefix("broadcast_type:")) {
        "text" -> AdminCallbackAction.BroadcastType(AdminBroadcastType.TEXT)
        "photo" -> AdminCallbackAction.BroadcastType(AdminBroadcastType.PHOTO)
        "video" -> AdminCallbackAction.BroadcastType(AdminBroadcastType.VIDEO)
        else -> null
    }
}
