package app.services

import app.db.DatabaseFactory
import app.db.UsersTable
import app.util.ClockProvider
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.time.ZoneOffset

private const val DEFAULT_LANGUAGE = "en"

data class UserProfile(
    val telegramId: Long,
    val language: String,
    val createdAt: Instant
)

class UserService {
    suspend fun ensureUser(telegramId: Long, preferredLanguage: String?): UserProfile {
        val existing = findUser(telegramId)
        if (existing != null) return existing
        val normalizedLang = preferredLanguage?.lowercase() ?: DEFAULT_LANGUAGE
        val now = Instant.now(ClockProvider.clock)
        DatabaseFactory.dbQuery {
            UsersTable.insert {
                it[UsersTable.telegramId] = telegramId
                it[UsersTable.language] = normalizedLang
                it[UsersTable.createdAt] = now.atZone(ZoneOffset.UTC).toLocalDateTime()
            }
        }
        return UserProfile(telegramId, normalizedLang, now)
    }

    suspend fun updateLanguage(telegramId: Long, language: String) {
        DatabaseFactory.dbQuery {
            UsersTable.update({ UsersTable.telegramId eq telegramId }) {
                it[UsersTable.language] = language
            }
        }
    }

    suspend fun findUser(telegramId: Long): UserProfile? {
        return DatabaseFactory.dbQuery {
            UsersTable.select { UsersTable.telegramId eq telegramId }
                .limit(1)
                .map(::toUser)
                .firstOrNull()
        }
    }

    suspend fun listAllUserIds(): List<Long> {
        return DatabaseFactory.dbQuery {
            UsersTable.slice(UsersTable.telegramId)
                .selectAll()
                .map { it[UsersTable.telegramId] }
        }
    }

    private fun toUser(row: ResultRow): UserProfile {
        val created = row[UsersTable.createdAt].atZone(ZoneOffset.UTC).toInstant()
        return UserProfile(
            telegramId = row[UsersTable.telegramId],
            language = row[UsersTable.language],
            createdAt = created
        )
    }
}
