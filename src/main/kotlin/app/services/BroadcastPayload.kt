package app.services

sealed class BroadcastPayload {
    data class Text(val text: String) : BroadcastPayload()
    data class Photo(val fileId: String, val caption: String?) : BroadcastPayload()
    data class Video(val fileId: String, val caption: String?) : BroadcastPayload()
}

enum class AdminBroadcastType {
    TEXT,
    PHOTO,
    VIDEO
}
