package org.kmprtc.server.config

import org.kmprtc.server.models.ConferenceRoomTable
import org.kmprtc.server.models.RecordingTable
import org.kmprtc.server.models.UserTable
import org.kmprtc.server.models.DeviceTokenTable
import org.kmprtc.server.models.SipAccountTable
import org.kmprtc.server.models.WebhookEventTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {

    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: ApplicationConfig) {

        val dbConfig = config.config("database")

        val host = dbConfig.property("host").getString()
        val port = dbConfig.property("port").getString()
        val name = dbConfig.property("name").getString()
        val user = dbConfig.property("user").getString()
        val password = dbConfig.property("password").getString().trim()
        val driver = dbConfig.property("driverClass").getString()
        val poolSize = dbConfig.property("maxPoolSize").getString().toInt()
        val retries = dbConfig.propertyOrNull("connectRetries")?.getString()?.toIntOrNull() ?: 30

        val dbUrl = "jdbc:mariadb://$host:$port/$name?useUnicode=true&characterEncoding=utf8mb4"

        val hikariConfig = HikariConfig().apply {
            this.driverClassName = driver
            this.jdbcUrl = dbUrl
            this.username = user
            this.password = password

            this.maximumPoolSize = poolSize
            this.minimumIdle = 1
            this.isAutoCommit = false
            this.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            this.connectionTimeout = 5_000
            // Fail vite si la base n'est pas prete: on gere nous-memes les essais ci-dessous.
            this.initializationFailTimeout = 1
        }

        // MariaDB est lance par Supervisor en parallele de Ktor: on attend qu'elle soit prete.
        var attempt = 0
        var dataSource: HikariDataSource? = null
        while (dataSource == null) {
            try {
                dataSource = HikariDataSource(hikariConfig)
            } catch (t: Throwable) {
                attempt++
                if (attempt >= retries) throw t
                log.warn("Database {}:{} not ready (attempt {}/{}): {}", host, port, attempt, retries, t.message)
                Thread.sleep(2_000)
            }
        }
        log.info("Connected to MariaDB {}:{}/{} as {}", host, port, name, user)
        Database.connect(dataSource)

        transaction {
            // NOTE: la table "cdr" appartient a Asterisk (cdr_adaptive_odbc): Ktor la lit seulement.
            SchemaUtils.createMissingTablesAndColumns(
                UserTable,
                DeviceTokenTable,
                SipAccountTable,
                ConferenceRoomTable,
                WebhookEventTable,
                RecordingTable,
            )
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
