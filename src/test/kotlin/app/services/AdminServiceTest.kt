package app.services

import app.DatabaseConfig
import app.db.DatabaseFactory
import app.db.UsersTable
import app.util.ClockProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert

class AdminServiceTest {

    @AfterTest
    fun tearDown() {
        ClockProvider.set(Clock.systemUTC())
    }

    @Test
    fun `collectOverview counts blocked users`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2024-01-31T00:00:00Z"), ZoneOffset.UTC)
        ClockProvider.set(clock)
        initDatabase()
        val now = clock.instant().atZone(ZoneOffset.UTC).toLocalDateTime()
        val recentBlock = now.minusDays(5)
        val oldBlock = now.minusDays(60)

        DatabaseFactory.dbQuery {
            UsersTable.insert {
                it[telegramId] = 1L
                it[locale] = null
                it[telegramLangCode] = null
                it[languageSelected] = false
                it[conversationState] = null
                it[mode] = null
                it[activeMode] = null
                it[lastMenuMessageId] = null
                it[lastWelcomeImageMessageId] = null
                it[lastWelcomeGreetingMessageId] = null
                it[lastStartCommandMessageId] = null
                it[isBlocked] = false
                it[blockedAt] = null
                it[createdAt] = now
            }
            UsersTable.insert {
                it[telegramId] = 2L
                it[locale] = null
                it[telegramLangCode] = null
                it[languageSelected] = false
                it[conversationState] = null
                it[mode] = null
                it[activeMode] = null
                it[lastMenuMessageId] = null
                it[lastWelcomeImageMessageId] = null
                it[lastWelcomeGreetingMessageId] = null
                it[lastStartCommandMessageId] = null
                it[isBlocked] = true
                it[blockedAt] = recentBlock
                it[createdAt] = now
            }
            UsersTable.insert {
                it[telegramId] = 3L
                it[locale] = null
                it[telegramLangCode] = null
                it[languageSelected] = false
                it[conversationState] = null
                it[mode] = null
                it[activeMode] = null
                it[lastMenuMessageId] = null
                it[lastWelcomeImageMessageId] = null
                it[lastWelcomeGreetingMessageId] = null
                it[lastStartCommandMessageId] = null
                it[isBlocked] = true
                it[blockedAt] = oldBlock
                it[createdAt] = now
            }
        }

        val service = AdminService()
        val overview = service.collectOverview()

        assertEquals(3L, overview.totalUsers)
        assertEquals(2L, overview.blockedUsers)
        assertEquals(1L, overview.blockedUsersLast30Days)
    }

    private fun initDatabase() {
        val config = DatabaseConfig(
            driver = "org.sqlite.JDBC",
            url = "jdbc:sqlite:file:admin-service-${UUID.randomUUID()}?mode=memory&cache=shared",
            maximumPoolSize = 1
        )
        DatabaseFactory.init(config)
    }
}
