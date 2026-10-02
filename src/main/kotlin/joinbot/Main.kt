package joinbot

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    var ds: HikariDataSource? = null
    try {
        val cfg = loadConfig(System::getenv)
        val crypto = Crypto(cfg.dataKeyset)
        ds = createDataSource(cfg)
        migrate(ds)
        verifyKeyset(connectExposed(ds), crypto)
    } catch (e: Exception) {
        // Class name only: messages can carry config values or key material.
        LoggerFactory.getLogger("joinbot.Main").error("startup failed: {}", e.javaClass.simpleName)
        ds?.close()
        exitProcess(1)
    }
}
