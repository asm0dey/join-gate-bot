package joinbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.GeneralSecurityException
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
private val p = Profile("Ann", "ann")
private val form = Form("hi", listOf(Text("a4", "Q")))

private fun group(db: Database, vararg chats: Long) = GroupRepo(db).let { g -> chats.forEach { g.upsert(it, "g$it", true) } }

class ReposTest : StringSpec({
    "form save is optimistic" {
        val db = testDb("form save is optimistic"); group(db, 1)
        val forms = FormRepo(db)
        forms.save(1, form, 0, 9, now) shouldBe 1
        forms.save(1, form, 0, 9, now) shouldBe null
        val f2 = form.copy(welcome = "v2")
        forms.save(1, f2, 1, 9, now) shouldBe 2
        forms.current(1) shouldBe (2 to f2)
        forms.version(1, 1) shouldBe form
        forms.all(1).map { it.first } shouldBe listOf(1, 2)
    }

    "session answers are sealed" {
        val db = testDb("session answers are sealed"); group(db, 1)
        val sessions = SessionRepo(db, testCrypto())
        val s = Session(1, 1, 1, 0, SessionState(p, mapOf("a4" to "secret")), "en", now)
        sessions.put(s)
        val raw = transaction(db) { FormSessions.selectAll().single()[FormSessions.answers] }
        String(raw, Charsets.ISO_8859_1).contains("secret") shouldBe false
        sessions.get(1, 1) shouldBe s
    }

    "session aad binds the row" {
        val db = testDb("session aad binds the row"); group(db, 1)
        val sessions = SessionRepo(db, testCrypto())
        sessions.put(Session(1, 1, 1, 0, SessionState(p, mapOf("a4" to "x")), null, now))
        sessions.put(Session(2, 1, 1, 0, SessionState(p), null, now))
        transaction(db) {
            val bytes = FormSessions.selectAll().where { FormSessions.userId eq 1L }.single()[FormSessions.answers]
            FormSessions.update({ FormSessions.userId eq 2L }) { it[answers] = bytes }
        }
        sessions.get(1, 1) shouldNotBe null
        shouldThrow<GeneralSecurityException> { sessions.get(2, 1) }
    }

    "decide is first-wins" {
        val db = testDb("decide is first-wins"); group(db, 1)
        val subs = SubmissionRepo(db, testCrypto())
        val id = subs.create(1, 5, 1, p, mapOf("a4" to "v"), Status.PENDING, now)
        subs.get(id)!!.let { it.profile shouldBe p; it.answers shouldBe mapOf("a4" to "v") }
        subs.decide(id, Status.APPROVED, 7, now) shouldBe true
        subs.decide(id, Status.REJECTED, 8, now) shouldBe false
        subs.get(id)!!.let { it.decidedBy shouldBe 7; it.status shouldBe Status.APPROVED }
    }

    "revert makes it decidable again" {
        val db = testDb("revert makes it decidable again"); group(db, 1)
        val subs = SubmissionRepo(db, testCrypto())
        val id = subs.create(1, 5, 1, p, null, Status.PENDING, now)
        subs.decide(id, Status.APPROVED, 7, now) shouldBe true
        subs.revert(id, Status.APPROVED, 8) shouldBe false // not 8's decision
        subs.revert(id, Status.REJECTED, 7) shouldBe false // not that decision
        subs.get(id)!!.status shouldBe Status.APPROVED
        subs.revert(id, Status.APPROVED, 7) shouldBe true
        subs.get(id)!!.let { it.status shouldBe Status.PENDING; it.decidedBy shouldBe null; it.decidedAt shouldBe null }
        subs.decide(id, Status.REJECTED, 8, now) shouldBe true
        subs.get(id)!!.decidedBy shouldBe 8
    }

    "transition moves only the named admin's decision" {
        val db = testDb("transition"); group(db, 1)
        val subs = SubmissionRepo(db, testCrypto())
        val id = subs.create(1, 5, 1, p, null, Status.PENDING, now)
        subs.transition(id, Status.PENDING, 7, Status.WITHDRAWN) shouldBe false // undecided: decided_by is null
        subs.decide(id, Status.APPROVED, 7, now) shouldBe true
        subs.transition(id, Status.APPROVED, 8, Status.WITHDRAWN) shouldBe false
        subs.transition(id, Status.APPROVED, 7, Status.WITHDRAWN) shouldBe true
        subs.get(id)!!.let { it.status shouldBe Status.WITHDRAWN; it.decidedBy shouldBe 7; it.decidedAt shouldBe now }
        subs.transition(id, Status.APPROVED, 7, Status.WITHDRAWN) shouldBe false
    }

    "unreachable submission has null answers" {
        val db = testDb("unreachable submission has null answers"); group(db, 1)
        val subs = SubmissionRepo(db, testCrypto())
        subs.get(subs.create(1, 5, null, p, null, Status.PENDING, now))!!.answers shouldBe null
    }

    "deleteOlderThan spares that chat's PENDING rows and other chats, and cascades review messages" {
        val db = testDb("deleteOlderThan only touches that chat"); group(db, 1, 2)
        val subs = SubmissionRepo(db, testCrypto())
        val old = now.minus(10, ChronoUnit.DAYS)
        val cutoff = now.minus(5, ChronoUnit.DAYS)
        val oldC1 = subs.create(1, 5, 1, p, null, Status.REJECTED, old)
        val newC1 = subs.create(1, 6, 1, p, null, Status.REJECTED, now)
        val oldC2 = subs.create(2, 5, 1, p, null, Status.REJECTED, old)
        val oldPending = subs.create(1, 8, 1, p, null, Status.PENDING, old)
        subs.addReviewMessage(oldC1, 7, 100)
        subs.addReviewMessage(newC1, 7, 101)
        subs.reviewMessages(oldC1) shouldBe listOf(7L to 100L)
        subs.deleteOlderThan(1, cutoff) shouldBe 1
        subs.get(oldC1) shouldBe null
        subs.get(newC1) shouldNotBe null
        subs.get(oldC2) shouldNotBe null
        subs.get(oldPending) shouldNotBe null
        subs.reviewMessages(oldC1) shouldBe emptyList()
        subs.reviewMessages(newC1) shouldBe listOf(7L to 101L)
    }

    "active() returns the non-waiting session" {
        val db = testDb("active returns the non-waiting session"); group(db, 1, 2)
        val sessions = SessionRepo(db, testCrypto())
        sessions.put(Session(1, 2, 1, WAITING, SessionState(p), null, now))
        sessions.put(Session(1, 1, 1, 0, SessionState(p), null, now))
        sessions.active(1)!!.chatId shouldBe 1
        sessions.forUser(1).size shouldBe 2
    }

    "concurrent saves of the same base yield one winner" {
        val db = testDb("concurrent saves same base"); group(db, 1)
        val forms = FormRepo(db)
        val out = java.util.concurrent.ConcurrentLinkedQueue<Int?>()
        (1..2).map { i -> kotlin.concurrent.thread { out.add(forms.save(1, form.copy(welcome = "w$i"), 0, 9, now)) } }
            .forEach { it.join() }
        val results = out.toList()
        results.filterNotNull() shouldBe listOf(1)
        forms.current(1)!!.first shouldBe 1
    }

    "submission bytes are sealed and bound to the id" {
        val db = testDb("submission bytes sealed"); group(db, 1)
        val subs = SubmissionRepo(db, testCrypto())
        val a = subs.create(1, 5, 1, Profile("Zed", "zed"), mapOf("a4" to "secret"), Status.PENDING, now)
        val b = subs.create(1, 6, 1, p, mapOf("a4" to "x"), Status.PENDING, now)
        transaction(db) {
            val ra = Submissions.selectAll().where { Submissions.id eq a }.single()
            val raw = String(ra[Submissions.profile], Charsets.ISO_8859_1) + String(ra[Submissions.answers]!!, Charsets.ISO_8859_1)
            raw.contains("Zed") shouldBe false
            raw.contains("secret") shouldBe false
            Submissions.update({ Submissions.id eq b }) { it[profile] = ra[Submissions.profile]; it[answers] = ra[Submissions.answers] }
        }
        subs.get(a) shouldNotBe null
        shouldThrow<GeneralSecurityException> { subs.get(b) }
    }

    "migrate re-keys a group and everything under it, reseals sessions, and is idempotent" {
        val db = testDb("migrate re-keys"); val crypto = testCrypto()
        val groups = GroupRepo(db); val forms = FormRepo(db)
        val sessions = SessionRepo(db, crypto); val subs = SubmissionRepo(db, crypto)
        groups.upsert(1, "Old", true); groups.setRetention(1, 30); groups.markNudged(1, now)
        forms.save(1, form, 0, 9, now)
        val st = SessionState(p, mapOf("a4" to "secret"))
        sessions.put(Session(5, 1, 1, 0, st, "en", now))
        val sid = subs.create(1, 5, 1, p, mapOf("a4" to "x"), Status.PENDING, now)
        subs.addReviewMessage(sid, 7, 100)

        groups.migrate(1, 2, sessions) shouldBe true
        groups.get(1) shouldBe null
        groups.get(2) shouldBe Group(2, "Old", true, 30, now)
        forms.current(2) shouldBe (1 to form)
        sessions.get(5, 1) shouldBe null
        sessions.get(5, 2)!!.state shouldBe st
        subs.get(sid)!!.let { it.chatId shouldBe 2L; it.answers shouldBe mapOf("a4" to "x") }
        subs.reviewMessages(sid) shouldBe listOf(7L to 100L)

        groups.migrate(1, 2, sessions) shouldBe false
        groups.get(2)!!.retentionDays shouldBe 30
    }

    "migrate onto a supergroup row the bot already registered keeps that row's state" {
        val db = testDb("migrate onto existing"); val groups = GroupRepo(db)
        groups.upsert(1, "Old", true); groups.setRetention(1, 30)
        groups.upsert(2, "New", false)
        groups.migrate(1, 2, SessionRepo(db, testCrypto())) shouldBe true
        groups.get(2) shouldBe Group(2, "New", false, 30, null)
        groups.get(1) shouldBe null
    }
})
