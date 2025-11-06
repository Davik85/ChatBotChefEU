package app.services

import app.db.DatabaseFactory
import app.db.MessagesHistoryTable
import app.db.PremiumTable
import app.db.UsersTable
import app.util.ClockProvider
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
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
            // Users total
            val userCountExpr = UsersTable.id.count()
            val users = UsersTable
                .slice(listOf(userCountExpr))
                .selectAll()
                .single()[userCountExpr]
                .toLong()

            // Premium rows total
            val premiumCountExpr = PremiumTable.id.count()
            val premium = PremiumTable
                .slice(listOf(premiumCountExpr))
                .selectAll()
                .single()[premiumCountExpr]
                .toLong()

            // Distinct telegram_id for the last seven days via distinct() + count()
            val dau = MessagesHistoryTable
                .slice(listOf(MessagesHistoryTable.telegramId))
                .select(where = { MessagesHistoryTable.createdAt greaterEq sevenDaysAgo.atZone(ZoneOffset.UTC).toLocalDateTime() })
                .distinct()
                .count()
                .toLong()

            AdminStats(
                totalUsers = users,
                premiumUsers = premium,
                dau7 = dau
            )
        }
    }
}
