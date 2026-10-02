package joinbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ConfigTest : StringSpec({
    val full = mapOf("BOT_TOKEN" to "1:x", "DATA_KEYSET" to "{}", "DB_FILE_KEY" to "k", "DB_USER_PW" to "p")
    "defaults" { loadConfig(full::get).run {
        miniAppPort shouldBe 8080; webDir shouldBe "web/dist"; dbPath shouldBe "data/joinbot"; miniAppUrl shouldBe null } }
    "missing required var names it" { listOf("BOT_TOKEN", "DATA_KEYSET", "DB_FILE_KEY", "DB_USER_PW").forEach { k ->
        shouldThrow<IllegalArgumentException> { loadConfig((full - k)::get) }.message shouldContain k } }
    "toString redacts secrets" { loadConfig(full::get).toString().run {
        shouldNotContain("1:x"); shouldNotContain("\"k\""); shouldContain("***") } }
    "bad MINIAPP_PORT rejected" { shouldThrow<IllegalArgumentException> { loadConfig((full + ("MINIAPP_PORT" to "x"))::get) } }
})
