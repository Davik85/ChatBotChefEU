package app.db

import app.DatabaseConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import javax.sql.DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
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
}
