package app.services

import app.db.DatabaseFactory
import app.db.UsageCountersTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager

class UsageService {
    suspend fun incrementUsage(telegramId: Long): Int {
        return DatabaseFactory.dbQuery {
            val table = UsageCountersTable
            val sql = """
                INSERT INTO ${table.tableName} (${table.telegramId.name}, ${table.totalUsed.name})
                VALUES (?, 1)
                ON CONFLICT(${table.telegramId.name})
                DO UPDATE SET ${table.totalUsed.name} = ${table.tableName}.${table.totalUsed.name} + 1
                RETURNING ${table.totalUsed.name}
            """.trimIndent()

            val args = listOf(table.telegramId.columnType to telegramId)
            TransactionManager.current().exec(sql, args, StatementType.SELECT) { rs ->
                if (rs.next()) {
                    rs.getInt(1)
                } else {
                    null
                }
            } ?: fetchUsageInternal(telegramId)
        }
    }

    suspend fun getUsage(telegramId: Long): Int {
        return DatabaseFactory.dbQuery { fetchUsageInternal(telegramId) }
    }

    private fun fetchUsageInternal(telegramId: Long): Int {
        return UsageCountersTable.select(where = { UsageCountersTable.telegramId eq telegramId })
            .limit(1)
            .map(::toUsage)
            .firstOrNull()
            ?.totalUsed
            ?: 0
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
