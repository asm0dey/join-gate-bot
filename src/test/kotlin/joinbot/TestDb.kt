package joinbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

/** In-memory, unencrypted: the file cipher is a deployment concern, not a logic one. */
fun memDataSource(name: String): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        username = "sa"
        password = ""
        maximumPoolSize = 2
    })

fun testCrypto() = Crypto(KeysetGen.aead())

fun testDb(name: String): Database = memDataSource(name).let { migrate(it); connectExposed(it) }
