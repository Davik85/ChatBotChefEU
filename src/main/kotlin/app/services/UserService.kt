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

enum class ConversationState { AWAITING_GREETING }

data class UserProfile(
    val telegramId: Long,
    val locale: String?,
    val conversationState: ConversationState?,
    val createdAt: Instant,
    var activeMode: String?
)

class UserService {
    suspend fun ensureUser(telegramId: Long, preferredLanguage: String?): UserProfile {
        val existing = findUser(telegramId)
        val normalizedPreferred = normalizeLocale(preferredLanguage)
        if (existing != null) {
            if (existing.locale == null && normalizedPreferred != null) {
                updateLocale(telegramId, normalizedPreferred)
                return existing.copy(locale = normalizedPreferred)
            }
            return existing
        }
        val now = Instant.now(ClockProvider.clock)
        DatabaseFactory.dbQuery {
            UsersTable.insert {
                it[UsersTable.telegramId] = telegramId
                it[UsersTable.locale] = normalizedPreferred
                it[UsersTable.createdAt] = now.atZone(ZoneOffset.UTC).toLocalDateTime()
                it[UsersTable.mode] = null
                it[UsersTable.activeMode] = null
            }
        }
        return UserProfile(telegramId, normalizedPreferred, null, now, null)
    }

    suspend fun updateLocale(telegramId: Long, locale: String?) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.locale] = locale
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

    suspend fun getActiveMode(telegramId: Long): String? {
        return DatabaseFactory.dbQuery {
            UsersTable.slice(UsersTable.activeMode, UsersTable.mode)
                .select { UsersTable.telegramId eq telegramId }
                .limit(1)
                .map { row -> row[UsersTable.activeMode] ?: legacyMode(row[UsersTable.mode]) }
                .firstOrNull()
        }
    }

    suspend fun updateActiveMode(telegramId: Long, mode: String?) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.activeMode] = mode
            }
        }
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
        val activeMode = row[UsersTable.activeMode] ?: legacyMode(row[UsersTable.mode])
        return UserProfile(
            telegramId = row[UsersTable.telegramId],
            locale = row[UsersTable.locale],
            conversationState = stateRaw?.let { runCatching { ConversationState.valueOf(it) }.getOrNull() },
            createdAt = created,
            activeMode = activeMode
        )
    }

    private fun normalizeLocale(raw: String?): String? {
        val normalized = raw?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.substring(0, minOf(2, raw.length))
        return normalized?.takeIf { LanguageSupport.isSupported(it) }
    }

    private fun legacyMode(raw: String?): String? {
        return when (raw) {
            "RECIPES" -> "RECIPES"
            "CALORIES", "CALORIE_CALCULATOR" -> "CALORIES"
            "PRODUCT_INFO", "INGREDIENT_MACROS" -> "INGREDIENT"
            "HELP" -> "HELP"
            else -> null
        }
    }
}
