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

enum class UIMode {
    RECIPES,
    CALORIE_CALCULATOR,
    INGREDIENT_MACROS,
    HELP;

    companion object {
        fun fromRaw(raw: String?): UIMode? {
            if (raw.isNullOrBlank()) return null
            return when (raw) {
                "CALORIES" -> CALORIE_CALCULATOR
                "PRODUCT_INFO" -> INGREDIENT_MACROS
                else -> runCatching { valueOf(raw) }.getOrNull()
            }
        }
    }
}

data class UserProfile(
    val telegramId: Long,
    val locale: String?,
    val conversationState: ConversationState?,
    val createdAt: Instant,
    val mode: UIMode?
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

    suspend fun getMode(telegramId: Long): UIMode? {
        val raw = DatabaseFactory.dbQuery {
            UsersTable.slice(UsersTable.mode)
                .select { UsersTable.telegramId eq telegramId }
                .limit(1)
                .map { it[UsersTable.mode] }
                .firstOrNull()
        }
        return UIMode.fromRaw(raw)
    }

    suspend fun setMode(telegramId: Long, mode: UIMode?) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.mode] = mode?.name
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
        val modeRaw = row[UsersTable.mode]
        return UserProfile(
            telegramId = row[UsersTable.telegramId],
            locale = row[UsersTable.locale],
            conversationState = stateRaw?.let { runCatching { ConversationState.valueOf(it) }.getOrNull() },
            createdAt = created,
            mode = UIMode.fromRaw(modeRaw)
        )
    }

    private fun normalizeLocale(raw: String?): String? {
        val normalized = raw?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.substring(0, minOf(2, raw.length))
        return normalized?.takeIf { LanguageSupport.isSupported(it) }
    }
}
