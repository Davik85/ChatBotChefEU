package app.services

import app.db.DatabaseFactory
import app.db.ProcessedUpdatesTable
import app.util.ClockProvider
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.time.Instant
import java.time.ZoneOffset

class DeduplicationService {
    suspend fun markProcessed(updateId: Long): Boolean {
        return DatabaseFactory.dbQuery {
            val exists = ProcessedUpdatesTable
                .select { ProcessedUpdatesTable.updateId eq updateId }
                .limit(1)
                .toList()
                .isNotEmpty()

            if (exists) {
                false
            } else {
                val now = Instant.now(ClockProvider.clock)
                ProcessedUpdatesTable.insert {
                    it[ProcessedUpdatesTable.updateId] = updateId
                    it[ProcessedUpdatesTable.processedAt] = now.atZone(ZoneOffset.UTC).toLocalDateTime()
                }
                true
            }
        }
    }
}
