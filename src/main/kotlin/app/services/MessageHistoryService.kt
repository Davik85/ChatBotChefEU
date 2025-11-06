package app.services

import app.db.DatabaseFactory
import app.db.MessagesHistoryTable
import app.util.ClockProvider
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.time.Instant
import java.time.ZoneOffset

private const val HISTORY_LIMIT = 20

class MessageHistoryService {
    suspend fun append(telegramId: Long, role: String, content: String) {
        val now = Instant.now(ClockProvider.clock)
        DatabaseFactory.dbQuery {
            MessagesHistoryTable.insert {
                it[MessagesHistoryTable.telegramId] = telegramId
                it[MessagesHistoryTable.role] = role
                it[MessagesHistoryTable.content] = content
                it[MessagesHistoryTable.createdAt] = now.atZone(ZoneOffset.UTC).toLocalDateTime()
            }
        }
    }

    suspend fun loadRecent(telegramId: Long): List<StoredMessage> {
        return DatabaseFactory.dbQuery {
            MessagesHistoryTable.select(where = { MessagesHistoryTable.telegramId eq telegramId })
                .orderBy(MessagesHistoryTable.createdAt, order = SortOrder.DESC)
                .limit(HISTORY_LIMIT)
                .map(::toStoredMessage)
                .reversed()
        }
    }

    private fun toStoredMessage(row: ResultRow): StoredMessage {
        val created = row[MessagesHistoryTable.createdAt].atZone(ZoneOffset.UTC).toInstant()
        return StoredMessage(
            telegramId = row[MessagesHistoryTable.telegramId],
            role = row[MessagesHistoryTable.role],
            content = row[MessagesHistoryTable.content],
            createdAt = created
        )
    }
}

data class StoredMessage(
    val telegramId: Long,
    val role: String,
    val content: String,
    val createdAt: Instant
)
