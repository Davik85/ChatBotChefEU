package app.services

import app.db.DatabaseFactory
import app.db.MessagesHistoryTable
import app.db.PremiumTable
import app.db.UsersTable
import app.util.ClockProvider
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

data class AdminOverview(
    val totalUsers: Long,
    val active7Days: Long,
    val active30Days: Long,
    val activePremiumUsers: Long,
    val blockedUsers: Long,
    val blockedUsersLast30Days: Long
)

data class LanguageStat(
    val locale: String,
    val count: Long
)

data class AdminUserStatus(
    val userId: Long,
    val locale: String?,
    val premiumUntil: Instant?,
    val lastActivity: Instant?
)

class AdminService {
    suspend fun collectOverview(): AdminOverview {
        val now = Instant.now(ClockProvider.clock)
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)

        return DatabaseFactory.dbQuery {
            val totalUsersExpr = UsersTable.id.count()
            val totalUsers = UsersTable
                .slice(totalUsersExpr)
                .selectAll()
                .singleOrNull()
                ?.get(totalUsersExpr)
                ?.toLong()
                ?: 0L

            val activePremiumExpr = PremiumTable.id.count()
            val activePremium = PremiumTable
                .slice(activePremiumExpr)
                .select { PremiumTable.activeUntil greater now.atZone(ZoneOffset.UTC).toLocalDateTime() }
                .singleOrNull()
                ?.get(activePremiumExpr)
                ?.toLong()
                ?: 0L

            val active7 = countActiveUsersSince(sevenDaysAgo)
            val active30 = countActiveUsersSince(thirtyDaysAgo)

            val blockedExpr = UsersTable.id.count()
            val blockedTotal = UsersTable
                .slice(blockedExpr)
                .select { UsersTable.isBlocked eq true }
                .singleOrNull()
                ?.get(blockedExpr)
                ?.toLong()
                ?: 0L

            val blockedLast30 = UsersTable
                .slice(blockedExpr)
                .select {
                    (UsersTable.isBlocked eq true) and
                        (UsersTable.blockedAt greaterEq thirtyDaysAgo.atZone(ZoneOffset.UTC).toLocalDateTime())
                }
                .singleOrNull()
                ?.get(blockedExpr)
                ?.toLong()
                ?: 0L

            AdminOverview(
                totalUsers = totalUsers,
                active7Days = active7,
                active30Days = active30,
                activePremiumUsers = activePremium,
                blockedUsers = blockedTotal,
                blockedUsersLast30Days = blockedLast30
            )
        }
    }

    suspend fun collectLanguageStats(): List<LanguageStat> {
        return DatabaseFactory.dbQuery {
            val counter = UsersTable.id.count()
            UsersTable
                .slice(UsersTable.locale, counter)
                .selectAll()
                .groupBy(UsersTable.locale)
                .map { row ->
                    val locale = row[UsersTable.locale] ?: "unknown"
                    LanguageStat(locale = locale, count = row[counter].toLong())
                }
                .sortedByDescending { it.count }
        }
    }

    suspend fun findUserStatus(userId: Long): AdminUserStatus? {
        return DatabaseFactory.dbQuery {
            val userRow = UsersTable
                .select { UsersTable.telegramId eq userId }
                .limit(1)
                .firstOrNull()
                ?: return@dbQuery null

            val premiumUntil = PremiumTable
                .select { PremiumTable.telegramId eq userId }
                .limit(1)
                .map { row -> row[PremiumTable.activeUntil].atZone(ZoneOffset.UTC).toInstant() }
                .firstOrNull()

            val lastActivity = MessagesHistoryTable
                .select { MessagesHistoryTable.telegramId eq userId }
                .orderBy(MessagesHistoryTable.createdAt, SortOrder.DESC)
                .limit(1)
                .map { row -> row[MessagesHistoryTable.createdAt].atZone(ZoneOffset.UTC).toInstant() }
                .firstOrNull()

            AdminUserStatus(
                userId = userId,
                locale = userRow[UsersTable.locale],
                premiumUntil = premiumUntil,
                lastActivity = lastActivity
            )
        }
    }

    private fun countActiveUsersSince(since: Instant): Long {
        val sinceDateTime = since.atZone(ZoneOffset.UTC).toLocalDateTime()
        return MessagesHistoryTable
            .join(UsersTable, JoinType.INNER, MessagesHistoryTable.telegramId, UsersTable.telegramId)
            .slice(MessagesHistoryTable.telegramId)
            .select {
                (MessagesHistoryTable.createdAt greaterEq sinceDateTime) and (UsersTable.isBlocked eq false)
            }
            .withDistinct()
            .count()
            .toLong()
    }

    private fun org.jetbrains.exposed.sql.Query.withDistinct() = this.distinct()
}
