package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
private val p = Profile("Ann", "ann")
private const val CHAT = 1L

private fun setup(name: String) = testDb(name).also { GroupRepo(it).upsert(CHAT, "g", true) }

class CheckReposTest : StringSpec({
    "backfill marks every approved applicant passed" {
        val ds = memDataSource("backfill")
        migrate(ds, "1")
        val t1 = now.minusSeconds(100); val t2 = now
        ds.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("INSERT INTO group_chat (chat_id, title, active) VALUES ($CHAT, 'g', TRUE)")
                fun sub(user: Int, status: String, at: Instant?) = s.execute(
                    "INSERT INTO submission (chat_id, user_id, profile, status, decided_at, created_at) VALUES " +
                        "($CHAT, $user, X'00', '$status', ${at?.let { "TIMESTAMP WITH TIME ZONE '$it'" } ?: "NULL"}, TIMESTAMP WITH TIME ZONE '$t1')")
                sub(5, "APPROVED", t1); sub(5, "APPROVED", t2); sub(6, "REJECTED", t1)
            }
        }
        migrate(ds)
        val db = connectExposed(ds)
        val members = MemberRepo(db)
        members.passedAt(CHAT, 5) shouldBe t2
        members.known(CHAT, 6) shouldBe false
        transaction(db) { Members.selectAll().count() } shouldBe 1L
    }

    "latest picks newest per person, then filters" {
        val db = setup("latest"); val subs = SubmissionRepo(db, testCrypto())
        subs.create(CHAT, 5, 1, p, null, Status.REJECTED, now)
        subs.create(CHAT, 5, 1, p, null, Status.PENDING, now, Kind.CHECK)
        subs.create(CHAT, 6, 1, p, null, Status.APPROVED, now)
        subs.latest(CHAT, null).map { it.id } shouldBe listOf(3L, 2L)
        subs.latest(CHAT, Status.REJECTED).shouldBeEmpty()
        subs.latest(CHAT, Status.PENDING).map { it.id } shouldBe listOf(2L)
        subs.pendingCheck(CHAT, 5)!!.id shouldBe 2L
        subs.pendingCheck(CHAT, 6) shouldBe null
    }

    "close is conditional" {
        val checks = CheckRepo(setup("close"))
        checks.open(CHAT, now, 9, now)
        checks.close(CHAT, now) shouldBe true
        checks.close(CHAT, now) shouldBe false
        checks.openCheck(CHAT) shouldBe null
        checks.get(CHAT)!!.closedAt shouldBe now
    }

    "reopen clears messages and notices" {
        val checks = CheckRepo(setup("reopen"))
        checks.open(CHAT, now, 9, now); checks.addMessage(CHAT, 7); checks.notice(CHAT, 9, false)
        checks.undelivered(9) shouldBe listOf(CHAT)
        checks.close(CHAT, now)
        checks.open(CHAT, now.plusSeconds(60), 9, now)
        checks.messages(CHAT).shouldBeEmpty(); checks.undelivered(9).shouldBeEmpty()
        checks.openCheck(CHAT)!!.deadline shouldBe now.plusSeconds(60)
    }

    "due returns only open checks past deadline" {
        val db = setup("due"); GroupRepo(db).upsert(2, "h", true); GroupRepo(db).upsert(3, "i", true)
        val checks = CheckRepo(db)
        checks.open(1, now, 9, now); checks.open(2, now.plusSeconds(60), 9, now); checks.open(3, now, 9, now); checks.close(3, now)
        checks.due(now).map { it.chatId } shouldBe listOf(1L)
    }

    "member pass, unpassed, passedGroups" {
        val db = setup("members"); GroupRepo(db).upsert(2, "h", true)
        val m = MemberRepo(db)
        m.seen(CHAT, 1); m.seen(CHAT, 2); m.pass(CHAT, 2, now); m.pass(2, 2, now)
        m.seen(CHAT, 2) // must not clear passed_at
        m.unpassed(CHAT) shouldBe listOf(1L)
        m.passedGroups(2).shouldContainExactlyInAnyOrder(CHAT, 2L)
        m.count(CHAT) shouldBe 2
        m.remove(CHAT, 1) shouldBe true; m.known(CHAT, 1) shouldBe false
        m.remove(CHAT, 1) shouldBe false
    }
})
