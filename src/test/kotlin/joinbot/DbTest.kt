package joinbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DbTest : StringSpec({
    "migrates and Exposed tables match" {
        transaction(testDb("drift")) {
            // One-directional: statements Exposed would run to match Tables.kt. Catches a column
            // in Tables.kt that V1__initial.sql lacks, not the reverse.
            @Suppress("DEPRECATION") // replacement is in exposed-migration-jdbc, which we don't use
            org.jetbrains.exposed.v1.jdbc.SchemaUtils.statementsRequiredToActualizeScheme(
                GroupChats, Forms, FormSessions, Submissions, ReviewMessages, BotUsers, Canary,
            ).shouldBeEmpty()
        }
    }
    "canary accepts same keyset, rejects another" {
        val db = testDb("canary")
        val c = testCrypto()
        verifyKeyset(db, c)
        verifyKeyset(db, c)
        shouldThrow<IllegalStateException> { verifyKeyset(db, testCrypto()) }
    }
})
