package app.db

import app.DatabaseConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.sql.DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
        ensureSqliteDir(config)
        val dataSource = hikari(config)
        Database.connect(dataSource)
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                UsersTable,
                PremiumTable,
                UsageCountersTable,
                MessagesHistoryTable,
                ProcessedUpdatesTable
            )
        }
    }

    suspend fun <T> dbQuery(block: () -> T): T {
        return newSuspendedTransaction(Dispatchers.IO) { block() }
    }

    private fun hikari(config: DatabaseConfig): DataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            driverClassName = config.driver
            maximumPoolSize = config.maximumPoolSize
            idleTimeout = config.idleTimeout.toMillis()
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(hikariConfig)
    }

    // Create parent directory for SQLite file if needed (works on Win/macOS/Linux)
    private fun ensureSqliteDir(config: DatabaseConfig) {
        if (config.driver != "org.sqlite.JDBC") return
        val prefix = "jdbc:sqlite:"
        if (!config.url.startsWith(prefix)) return

        // Supported forms: jdbc:sqlite:./data/db.sqlite, jdbc:sqlite:/data/db.sqlite, jdbc:sqlite:file:db?mode=...
        // We handle the common file-path forms; URI variants are ignored on purpose.
        val raw = config.url.removePrefix(prefix)
        if (raw.startsWith("file:")) return // skip URI forms

        // On Windows an absolute like "/data/..." becomes "C:\data\..."
        val path: Path = Path.of(raw)
        val parent = path.parent ?: return
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }
}
