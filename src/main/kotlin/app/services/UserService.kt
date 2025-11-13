package app.services

import app.LanguageSupport
import app.db.DatabaseFactory
import app.db.UsersTable
import app.util.ClockProvider
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset

enum class ConversationState {
    AWAITING_LANGUAGE_SELECTION,
    AWAITING_GREETING,
    ADMIN_AWAITING_BROADCAST_TEXT,
    ADMIN_CONFIRM_BROADCAST,
    ADMIN_AWAITING_USER_STATUS,
    ADMIN_AWAITING_GRANT_PREMIUM
}

data class UserProfile(
    val telegramId: Long,
    val locale: String?,
    val conversationState: ConversationState?,
    val createdAt: Instant,
    var mode: ConversationMode?,
    var lastMenuMessageId: Long?,
    var lastWelcomeImageMessageId: Long?,
    var lastWelcomeGreetingMessageId: Long?,
    var lastStartCommandMessageId: Long?,
    var languageSelected: Boolean,
    val telegramLangCode: String?
)

class UserService {
    suspend fun ensureUser(telegramId: Long, preferredLanguage: String?): UserProfile {
        val existing = findUser(telegramId)
        val normalizedPreferred = normalizeLocale(preferredLanguage)
        if (existing != null) {
            if (normalizedPreferred != null && normalizedPreferred != existing.telegramLangCode) {
                updateTelegramLanguageCode(telegramId, normalizedPreferred)
                return existing.copy(telegramLangCode = normalizedPreferred)
            }
            return existing
        }
        val now = Instant.now(ClockProvider.clock)
        DatabaseFactory.dbQuery {
            UsersTable.insert {
                it[UsersTable.telegramId] = telegramId
                it[UsersTable.locale] = null
                it[UsersTable.telegramLangCode] = normalizedPreferred
                it[UsersTable.languageSelected] = false
                it[UsersTable.createdAt] = now.atZone(ZoneOffset.UTC).toLocalDateTime()
                it[UsersTable.conversationState] = ConversationState.AWAITING_LANGUAGE_SELECTION.name
                it[UsersTable.mode] = null
                it[UsersTable.activeMode] = null
                it[UsersTable.lastMenuMessageId] = null
                it[UsersTable.lastWelcomeImageMessageId] = null
                it[UsersTable.lastWelcomeGreetingMessageId] = null
                it[UsersTable.lastStartCommandMessageId] = null
            }
        }
        return UserProfile(
            telegramId = telegramId,
            locale = null,
            conversationState = ConversationState.AWAITING_LANGUAGE_SELECTION,
            createdAt = now,
            mode = null,
            lastMenuMessageId = null,
            lastWelcomeImageMessageId = null,
            lastWelcomeGreetingMessageId = null,
            lastStartCommandMessageId = null,
            languageSelected = false,
            telegramLangCode = normalizedPreferred
        )
    }

    suspend fun updateLocale(telegramId: Long, locale: String?, markSelected: Boolean = true) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.locale] = locale
                if (markSelected) {
                    it[UsersTable.languageSelected] = locale != null
                } else {
                    it[UsersTable.languageSelected] = false
                }
            }
        }
    }

    private suspend fun updateTelegramLanguageCode(telegramId: Long, languageCode: String?) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.telegramLangCode] = languageCode
            }
        }
    }

    suspend fun updateConversationState(telegramId: Long, state: ConversationState?) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.conversationState] = state?.name
            }
        }
    }

    suspend fun findUser(telegramId: Long): UserProfile? {
        return DatabaseFactory.dbQuery {
            UsersTable.select(where = { UsersTable.telegramId eq telegramId })
                .limit(1)
                .map(::toUser)
                .firstOrNull()
        }
    }

    suspend fun getMode(telegramId: Long): ConversationMode? {
        return DatabaseFactory.dbQuery {
            UsersTable.slice(UsersTable.activeMode, UsersTable.mode)
                .select { UsersTable.telegramId eq telegramId }
                .limit(1)
                .mapNotNull { row ->
                    val stored = row[UsersTable.activeMode]
                    stored?.let { runCatching { ConversationMode.valueOf(it) }.getOrNull() }
                        ?: legacyMode(row[UsersTable.mode])
                }
                .firstOrNull()
        }
    }

    suspend fun updateMode(telegramId: Long, mode: ConversationMode?) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.activeMode] = mode?.name
            }
        }
    }

    suspend fun updateLastMenuMessageId(telegramId: Long, messageId: Long?): Long? {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.lastMenuMessageId] = messageId
            }
        }
        return messageId
    }

    suspend fun updateLastWelcomeImageMessageId(telegramId: Long, messageId: Long?): Long? {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.lastWelcomeImageMessageId] = messageId
            }
        }
        return messageId
    }

    suspend fun updateLastWelcomeGreetingMessageId(telegramId: Long, messageId: Long?): Long? {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.lastWelcomeGreetingMessageId] = messageId
            }
        }
        return messageId
    }

    suspend fun updateLastStartCommandMessageId(telegramId: Long, messageId: Long?): Long? {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.lastStartCommandMessageId] = messageId
            }
        }
        return messageId
    }

    suspend fun listAllUserIds(): List<Long> {
        return DatabaseFactory.dbQuery {
            UsersTable.slice(listOf(UsersTable.telegramId))
                .selectAll()
                .map { it[UsersTable.telegramId] }
        }
    }

    private fun toUser(row: ResultRow): UserProfile {
        val created = row[UsersTable.createdAt].atZone(ZoneOffset.UTC).toInstant()
        val stateRaw = row[UsersTable.conversationState]
        val storedMode = row[UsersTable.activeMode]
        val mode = storedMode?.let { runCatching { ConversationMode.valueOf(it) }.getOrNull() }
            ?: legacyMode(row[UsersTable.mode])
        return UserProfile(
            telegramId = row[UsersTable.telegramId],
            locale = row[UsersTable.locale],
            conversationState = stateRaw?.let { runCatching { ConversationState.valueOf(it) }.getOrNull() },
            createdAt = created,
            mode = mode,
            lastMenuMessageId = row[UsersTable.lastMenuMessageId],
            lastWelcomeImageMessageId = row[UsersTable.lastWelcomeImageMessageId],
            lastWelcomeGreetingMessageId = row[UsersTable.lastWelcomeGreetingMessageId],
            lastStartCommandMessageId = row[UsersTable.lastStartCommandMessageId],
            languageSelected = row[UsersTable.languageSelected],
            telegramLangCode = row[UsersTable.telegramLangCode]
        )
    }

    private fun normalizeLocale(raw: String?): String? {
        val normalized = raw?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.substring(0, minOf(2, raw.length))
        return normalized?.takeIf { LanguageSupport.isSupported(it) }
    }

    private fun legacyMode(raw: String?): ConversationMode? {
        return when (raw) {
            "RECIPES" -> ConversationMode.RECIPES
            "CALORIES", "CALORIE_CALCULATOR" -> ConversationMode.CALORIE
            "PRODUCT_INFO", "INGREDIENT_MACROS" -> ConversationMode.INGREDIENT
            "HELP" -> ConversationMode.HELP
            else -> null
        }
    }
}
