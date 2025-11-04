package app.services

import app.db.DatabaseFactory
import app.db.MessagesHistoryTable
import app.db.PremiumTable
import app.db.UsersTable
import app.util.ClockProvider
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.select
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class AdminStats(
    val totalUsers: Long,
    val premiumUsers: Long,
    val dau7: Long
)

class AdminService {
    suspend fun collectStats(): AdminStats {
        val now = Instant.now(ClockProvider.clock)
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)
        return DatabaseFactory.dbQuery {
            val users = UsersTable.slice(UsersTable.id.count()).selectAll().single()[UsersTable.id.count()]
            val premium = PremiumTable.slice(PremiumTable.id.count()).selectAll().single()[PremiumTable.id.count()]
            val dau = MessagesHistoryTable
                .slice(MessagesHistoryTable.telegramId.count(distinct = true))
                .select { MessagesHistoryTable.createdAt greaterEq sevenDaysAgo.atZone(ZoneOffset.UTC).toLocalDateTime() }
                .single()[MessagesHistoryTable.telegramId.count(distinct = true)]
            AdminStats(
                totalUsers = users,
                premiumUsers = premium,
                dau7 = dau
            )
        }
    }
}
