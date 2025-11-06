package app.services

import app.BillingConfig
import app.db.DatabaseFactory
import app.db.PremiumTable
import app.util.ClockProvider
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class PremiumService(private val billingConfig: BillingConfig) {
    suspend fun isPremiumActive(telegramId: Long): Boolean {
        val activeUntil = getPremiumUntil(telegramId) ?: return false
        val now = Instant.now(ClockProvider.clock)
        return activeUntil.isAfter(now)
    }

    suspend fun getPremiumUntil(telegramId: Long): Instant? {
        return DatabaseFactory.dbQuery {
            PremiumTable
                .select(where = { PremiumTable.telegramId eq telegramId })
                .limit(1)
                .map { row -> row[PremiumTable.activeUntil].atZone(ZoneOffset.UTC).toInstant() }
                .firstOrNull()
        }
    }

    suspend fun grantPremium(telegramId: Long, additionalDays: Long): Instant {
        val days = if (additionalDays > 0) additionalDays else billingConfig.premiumDurationDays
        val now = Instant.now(ClockProvider.clock)
        val newExpiry = getPremiumUntil(telegramId)?.takeIf { it.isAfter(now) }?.plus(days, ChronoUnit.DAYS)
            ?: now.plus(days, ChronoUnit.DAYS)

        DatabaseFactory.dbQuery {
            val expiry = newExpiry.atZone(ZoneOffset.UTC).toLocalDateTime()
            val updated = PremiumTable.update({ PremiumTable.telegramId eq telegramId }) {
                it[activeUntil] = expiry
            }
            if (updated == 0) {
                PremiumTable.insert {
                    it[PremiumTable.telegramId] = telegramId
                    it[activeUntil] = expiry
                }
            }
        }
        return newExpiry
    }

    suspend fun findExpiringWithin(daysBefore: List<Long>): List<Pair<Long, Instant>> {
        if (daysBefore.isEmpty()) return emptyList()
        val now = Instant.now(ClockProvider.clock)
        val windows = daysBefore.map { now.plus(it, ChronoUnit.DAYS) }
        return DatabaseFactory.dbQuery {
            PremiumTable
                .select(where = { PremiumTable.activeUntil greater now.atZone(ZoneOffset.UTC).toLocalDateTime() })
                .map { row ->
                    val userId = row[PremiumTable.telegramId]
                    val expiry = row[PremiumTable.activeUntil].atZone(ZoneOffset.UTC).toInstant()
                    userId to expiry
                }
                .mapNotNull { (userId, expiry) ->
                    val matches = windows.any { window ->
                        val windowStart = window.truncatedTo(ChronoUnit.DAYS)
                        val windowEnd = windowStart.plus(1, ChronoUnit.DAYS)
                        expiry.isAfter(windowStart) && expiry.isBefore(windowEnd)
                    }
                    if (matches) userId to expiry else null
                }
        }
    }
}
