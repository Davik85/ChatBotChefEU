package app

import app.db.DatabaseFactory
import app.services.DeduplicationService
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupTest {
    private val service = DeduplicationService()

    @BeforeTest
    fun setup() {
        val config = DatabaseConfig(
            driver = "org.sqlite.JDBC",
            url = "jdbc:sqlite:file:dedup?mode=memory&cache=shared"
        )
        DatabaseFactory.init(config)
    }

    @Test
    fun `duplicate updates are ignored`() = runBlocking {
        val first = service.markProcessed(123L)
        val second = service.markProcessed(123L)
        assertTrue(first)
        assertFalse(second)
    }
}
