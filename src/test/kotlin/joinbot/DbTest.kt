package joinbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.TimeZone
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DbTest : StringSpec({
    "migrates and Exposed tables match" {
        transaction(testDb("drift")) {
            // One-directional: statements Exposed would run to match Tables.kt. Catches a column
            // in Tables.kt that V1__initial.sql lacks, not the reverse.
            @Suppress("DEPRECATION") // replacement is in exposed-migration-jdbc, which we don't use
            org.jetbrains.exposed.v1.jdbc.SchemaUtils.statementsRequiredToActualizeScheme(
                GroupChats, Forms, FormSessions, Submissions, ReviewMessages, BotUsers, Canary,
                Members, Rechecks, RecheckMessages, RecheckNotices,
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
    "timestamp round-trips under a non-UTC default zone" {
        val db = testDb("ts")
        // H2 TIMESTAMP keeps microseconds, so that is the precision asserted.
        val t = Instant.ofEpochSecond(1_800_000_000, 123_456_000)
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try { transaction(db) {
            GroupChats.insert { it[chatId] = 1; it[title] = "t"; it[active] = true; it[nudgedAt] = t }
            GroupChats.selectAll().where { GroupChats.chatId eq 1 }.single()[GroupChats.nudgedAt] shouldBe t
        } } finally { TimeZone.setDefault(saved) }
    }
})
