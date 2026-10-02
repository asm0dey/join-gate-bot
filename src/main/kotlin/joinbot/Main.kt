package joinbot

import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    val cfg = loadConfig(System::getenv)
    val crypto = Crypto(cfg.dataKeyset)
    val ds = createDataSource(cfg)
    try {
        migrate(ds)
        verifyKeyset(connectExposed(ds), crypto)
    } catch (e: Exception) {
        LoggerFactory.getLogger("joinbot.Main").error("startup failed: {}", e.javaClass.simpleName)
        exitProcess(1)
    }
}
