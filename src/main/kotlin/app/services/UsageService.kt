package app.services

import app.db.DatabaseFactory
import app.db.UsageCountersTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

class UsageService {
    suspend fun incrementUsage(telegramId: Long): Int {
        return DatabaseFactory.dbQuery {
            val current = UsageCountersTable.select { UsageCountersTable.telegramId eq telegramId }
                .limit(1)
                .map(::toUsage)
                .firstOrNull()
            val newCount = (current?.totalUsed ?: 0) + 1
            val updated = UsageCountersTable.update({ UsageCountersTable.telegramId eq telegramId }) {
                it[totalUsed] = newCount
            }
            if (updated == 0) {
                UsageCountersTable.insert {
                    it[UsageCountersTable.telegramId] = telegramId
                    it[totalUsed] = newCount
                }
            }
            newCount
        }
    }

    suspend fun getUsage(telegramId: Long): Int {
        return DatabaseFactory.dbQuery {
            UsageCountersTable.select { UsageCountersTable.telegramId eq telegramId }
                .limit(1)
                .map(::toUsage)
                .firstOrNull()
                ?.totalUsed
                ?: 0
        }
    }

    private fun toUsage(row: ResultRow): UsageRecord {
        return UsageRecord(
            telegramId = row[UsageCountersTable.telegramId],
            totalUsed = row[UsageCountersTable.totalUsed]
        )
    }
}

data class UsageRecord(
    val telegramId: Long,
    val totalUsed: Int
)
