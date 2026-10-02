package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

private val ann = Profile("Ann", "ann")

private class PurgeEnv(name: String) {
    val db = testDb(name)
    val clock = TestClock()
    val tg = FakeTg()
    val groups = GroupRepo(db)
    val sessions = SessionRepo(db, testCrypto())
    val subs = SubmissionRepo(db, testCrypto())
    val users = BotUserRepo(db)
    val review = ReviewService(subs, FormRepo(db), groups, users, AdminCheck(tg, clock), tg, clock)
    val flow = ApplicantFlow(groups, FormRepo(db), sessions, subs, users, review, tg, clock)
    val purge = Purge(sessions, flow, subs, groups, clock)

    fun session(user: Long, chat: Long, age: Duration, step: Int = 0) =
        sessions.put(Session(user, chat, 1, step, SessionState(ann), "en", clock.instant() - age))
}

class PurgeTest : StringSpec({
    "idle session expires" {
        val e = PurgeEnv("purge-idle")
        e.groups.upsert(-1, "G", true)
        e.session(5, -1, Duration.ofDays(7).plusSeconds(1))
        e.session(6, -1, Duration.ofDays(6))
        e.purge.runOnce()
        e.tg.calls.contains("decline -1 5") shouldBe true
        e.tg.sent.single { it.chatId == 5L }.text shouldBe Texts.t("en", T.EXPIRED)
        e.subs.list(-1, Status.EXPIRED).single().let { it.userId shouldBe 5L; it.answers.shouldBeNull() }
        e.sessions.get(5, -1).shouldBeNull()
        e.sessions.get(6, -1).shouldNotBeNull()
        e.tg.calls.contains("decline -1 6") shouldBe false
    }

    "waiting session expires too" {
        val e = PurgeEnv("purge-waiting")
        e.groups.upsert(-1, "G", true)
        e.session(5, -1, Duration.ofDays(8), WAITING)
        e.purge.runOnce()
        e.sessions.get(5, -1).shouldBeNull()
        e.subs.list(-1, Status.EXPIRED).size shouldBe 1
    }

    "retention deletes only old submissions of that group" {
        val e = PurgeEnv("purge-retention")
        e.groups.upsert(-1, "A", true); e.groups.setRetention(-1, 30)
        e.groups.upsert(-2, "B", true); e.groups.setRetention(-2, 90)
        fun add(chat: Long, days: Long) =
            e.subs.create(chat, 5, 1, ann, mapOf("a" to "b"), Status.PENDING, e.clock.instant() - Duration.ofDays(days))
        val old1 = add(-1, 31); val fresh1 = add(-1, 29); val kept2 = add(-2, 31)
        e.purge.runOnce()
        e.subs.get(old1).shouldBeNull()
        e.subs.get(fresh1).shouldNotBeNull()
        e.subs.get(kept2).shouldNotBeNull()
    }

    "one failing decline does not stop the rest" {
        val e = PurgeEnv("purge-failing")
        e.groups.upsert(-1, "G", true)
        e.tg.throwOnDeclineFor += 1L
        e.session(1, -1, Duration.ofDays(8))
        e.session(2, -1, Duration.ofDays(8))
        e.purge.runOnce()
        e.sessions.get(2, -1).shouldBeNull()
        e.subs.list(-1, Status.EXPIRED).map { it.userId }.toSet() shouldBe setOf(1L, 2L)
    }
})
