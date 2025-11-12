package app.services

import app.util.ClockProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed class AdminConversationState {
    object AwaitingBroadcastText : AdminConversationState()
    data class BroadcastPreview(val text: String) : AdminConversationState()
    object AwaitingUserStatus : AdminConversationState()
    object AwaitingGrantPremium : AdminConversationState()
}

class AdminConversationStateService(
    private val timeout: Duration = Duration.ofMinutes(10)
) {
    private data class Entry(val state: AdminConversationState, val updatedAt: Instant)

    private val states = ConcurrentHashMap<Long, Entry>()

    fun set(userId: Long, state: AdminConversationState) {
        states[userId] = Entry(state, now())
    }

    fun get(userId: Long): AdminConversationState? {
        val entry = states[userId] ?: return null
        return if (isExpired(entry.updatedAt)) {
            states.remove(userId)
            null
        } else {
            entry.state
        }
    }

    fun clear(userId: Long) {
        states.remove(userId)
    }

    private fun isExpired(updatedAt: Instant): Boolean {
        return Duration.between(updatedAt, now()) > timeout
    }

    private fun now(): Instant = Instant.now(ClockProvider.clock)
}
