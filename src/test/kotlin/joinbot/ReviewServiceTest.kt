package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val CHAT = -100L
private const val APPLICANT = 5L
private val ann = Profile("Ann", "ann")
private val form = Form("hi", listOf(Text("q1", "Why join?"), Text("q2", "Where from?")))
private fun admin(id: Long, name: String = "A$id", canInvite: Boolean = true) = Admin(id, name, false, canInvite)

private class Env(name: String) {
    val db = testDb(name)
    val clock = TestClock()
    val tg = FakeTelegram()
    val groups = GroupRepo(db)
    val forms = FormRepo(db)
    val subs = SubmissionRepo(db, testCrypto())
    val users = BotUserRepo(db)
    val review = ReviewService(subs, forms, groups, users, AdminCheck(tg.bot, clock), tg.bot, clock)

    fun group(chat: Long = CHAT, vararg admins: Admin) {
        groups.upsert(chat, "Club$chat", true)
        forms.save(chat, form, 0, 9, clock.instant())
        tg.adminsOf[chat] = admins.toList()
    }

    fun pending(chat: Long = CHAT, answers: Map<String, String>? = mapOf("q1" to "fun", "q2" to "Oslo")) =
        subs.create(chat, APPLICANT, 1, ann, answers, Status.PENDING, clock.instant())

    fun alerts(cb: String) = tg.calls.filter { it.startsWith("answer $cb ") && it.endsWith(" alert") }
}

class ReviewServiceTest : StringSpec({
    "fan-out tries every decider; copies land with the reachable ones" {
        val e = Env("fan-out")
        e.group(CHAT, admin(1), admin(2), admin(3))
        e.users.started(1, "en"); e.users.started(2, "ru")
        e.tg.sendResultByChat[3] = Sent.Forbidden // never wrote to the bot
        val id = e.pending()
        e.review.submit(id)
        e.tg.sent.map { it.chatId } shouldBe listOf(1L, 2L, 3L)
        e.tg.sent.forEach { m -> m.buttons.flatten().map { it.data } shouldBe listOf("r|$id|a", "r|$id|j") }
        e.tg.sent[0].buttons.flatten().map { it.text } shouldBe listOf(Texts.t("en", T.APPROVE), Texts.t("en", T.REJECT))
        e.tg.sent[1].buttons.flatten().map { it.text } shouldBe listOf(Texts.t("ru", T.APPROVE), Texts.t("ru", T.REJECT))
        e.tg.sent[0].text shouldContain "Why join?: fun\nWhere from?: Oslo"
        e.subs.reviewMessages(id).map { it.first }.sorted() shouldBe listOf(1L, 2L)
    }

    "an admin who joined through the form gets copies without /start" {
        val e = Env("applicant-admin")
        e.group(CHAT, admin(1))
        e.users.setLang(1, "en") // what onJoinRequest records; the form was answered without /start
        val id = e.pending()
        e.review.submit(id)
        e.subs.reviewMessages(id).map { it.first } shouldBe listOf(1L)
        e.tg.calls.none { it.startsWith("send $CHAT ") } shouldBe true
    }

    "forbidden admin is marked and skipped" {
        val e = Env("forbidden")
        e.group(CHAT, admin(1), admin(2))
        e.users.started(1, "en"); e.users.started(2, "en")
        e.tg.sendResultByChat[1] = Sent.Forbidden
        val id = e.pending()
        e.review.submit(id)
        e.users.dmOk(1) shouldBe false
        e.subs.reviewMessages(id).map { it.first } shouldBe listOf(2L)
        e.tg.calls.none { it.startsWith("send $CHAT ") } shouldBe true
    }

    "two simultaneous approvals → one Telegram approve, both copies edited" {
        val e = Env("race")
        e.group(CHAT, admin(1, "Bob"), admin(2, "Eve"))
        e.users.started(1, "en"); e.users.started(2, "en")
        val id = e.pending()
        e.review.submit(id)
        val copies = e.subs.reviewMessages(id).map { it.second }.toSet()
        coroutineScope {
            launch(Dispatchers.Default) { e.review.onDecision(1, "c1", "r|$id|a") }
            launch(Dispatchers.Default) { e.review.onDecision(2, "c2", "r|$id|a") }
        }
        e.tg.calls.count { it.startsWith("approve") } shouldBe 1
        e.tg.edits.map { it.messageId }.toSet() shouldBe copies
        e.tg.edits.size shouldBe 2
        e.tg.edits.forEach { it.buttons shouldBe emptyList() }
        val winner = e.subs.get(id)!!.decidedBy!!
        val loserCb = if (winner == 1L) "c2" else "c1"
        val winnerName = if (winner == 1L) "Bob" else "Eve"
        e.alerts(loserCb) shouldBe listOf("answer $loserCb ${Texts.t("en", T.ALREADY_DECIDED, winnerName)} alert")
        e.tg.calls.count { it.contains(Texts.t("en", T.ALREADY_DECIDED, winnerName)) } shouldBe 1
    }

    "approval edits copies with the decider's name and tells the applicant in their language" {
        val e = Env("approve")
        e.group(CHAT, admin(1, "Bob"), admin(2, "Eve"))
        e.users.started(1, "en"); e.users.started(2, "ru"); e.users.started(APPLICANT, "ru")
        val id = e.pending()
        e.review.submit(id)
        e.review.onDecision(1, "c1", "r|$id|j")
        e.tg.calls.count { it == "decline $CHAT $APPLICANT" } shouldBe 1
        e.subs.get(id)!!.status shouldBe Status.REJECTED
        val byAdmin = e.subs.reviewMessages(id).toMap()
        e.tg.edits.single { it.chatId == 1L }.let { it.messageId shouldBe byAdmin[1]; it.text shouldEndWith Texts.t("en", T.DECIDED_BY_REJECTED, "Bob") }
        e.tg.edits.single { it.chatId == 2L }.text shouldEndWith Texts.t("ru", T.DECIDED_BY_REJECTED, "Bob")
        e.tg.sent.last().let { it.chatId shouldBe APPLICANT; it.text shouldBe Texts.t("ru", T.REJECTED_USER) }
    }

    "a decider who left the group is named neutrally" {
        val e = Env("left")
        e.group(CHAT, admin(1, "Bob"), admin(2, "Eve"))
        e.users.started(1, "en"); e.users.started(2, "en")
        val id = e.pending()
        e.review.submit(id)
        e.review.onDecision(1, "c1", "r|$id|a")
        e.tg.adminsOf[CHAT] = listOf(admin(2, "Eve"))
        e.review.onDecision(2, "c2", "r|$id|a")
        e.alerts("c2") shouldBe listOf("answer c2 ${Texts.t("en", T.ALREADY_DECIDED, Texts.t("en", T.AN_ADMIN))} alert")
    }

    "demoted admin gets alert, nothing decided" {
        val e = Env("demoted")
        e.group(CHAT, admin(1), admin(2))
        e.users.started(1, "en")
        val id = e.pending()
        e.review.submit(id)
        e.tg.adminsOf[CHAT] = listOf(admin(1, canInvite = false), admin(2))
        e.review.onDecision(1, "c1", "r|$id|a")
        e.subs.get(id)!!.status shouldBe Status.PENDING
        e.tg.calls.none { it.startsWith("approve") } shouldBe true
        e.alerts("c1") shouldBe listOf("answer c1 ${Texts.t("en", T.NOT_ADMIN_ANYMORE)} alert")
    }

    "inactive group refuses decisions" {
        val e = Env("inactive")
        e.group(CHAT, admin(1))
        e.users.started(1, "en")
        val id = e.pending()
        e.groups.upsert(CHAT, "Club", false)
        e.review.onDecision(1, "c1", "r|$id|a")
        e.subs.get(id)!!.status shouldBe Status.PENDING
        e.alerts("c1") shouldBe listOf("answer c1 ${Texts.t("en", T.REVIEW_SUSPENDED)} alert")
    }

    "bad or stale callback data" {
        val e = Env("stale")
        e.group(CHAT, admin(1))
        e.review.onDecision(1, "c1", "r|x|a")
        e.review.onDecision(1, "c2", "r|999|a")
        e.review.onDecision(1, "c3", "r|1|z")
        listOf("c1", "c2", "c3").forEach { e.alerts(it) shouldBe listOf("answer $it ${Texts.t(null, T.STALE_BUTTON)} alert") }
    }

    "withdrawn request" {
        val e = Env("withdrawn")
        e.group(CHAT, admin(1))
        e.users.started(1, "en"); e.users.started(APPLICANT, "en")
        val id = e.pending()
        e.review.submit(id)
        e.tg.decideResult = Decision.GONE
        e.review.onDecision(1, "c1", "r|$id|a")
        e.subs.get(id)!!.status shouldBe Status.WITHDRAWN
        e.tg.edits.single().text shouldEndWith Texts.t("en", T.WITHDRAWN)
        e.tg.sent.none { it.chatId == APPLICANT } shouldBe true
    }

    "transient error reverts" {
        val e = Env("transient")
        e.group(CHAT, admin(1))
        e.users.started(1, "en")
        val id = e.pending()
        e.review.submit(id)
        e.tg.decideResult = Decision.TRANSIENT
        e.review.onDecision(1, "c1", "r|$id|a")
        e.subs.get(id)!!.status shouldBe Status.PENDING
        e.alerts("c1") shouldBe listOf("answer c1 ${Texts.t("en", T.TRY_AGAIN)} alert")
        e.tg.edits shouldBe emptyList()
        e.tg.decideResult = Decision.OK
        e.review.onDecision(1, "c2", "r|$id|a")
        e.subs.get(id)!!.status shouldBe Status.APPROVED
        e.tg.edits.size shouldBe 1
    }

    "no reachable decider nudges at most hourly" {
        val e = Env("nudge")
        e.group(CHAT, admin(1))
        e.tg.sendResultByChat[1] = Sent.Forbidden
        fun nudges() = e.tg.calls.filter { it.startsWith("send $CHAT ") }
        e.review.submit(e.pending())
        e.clock.now = e.clock.now.plus(Duration.ofMinutes(30))
        e.review.submit(e.pending())
        nudges() shouldBe listOf("send $CHAT ${Texts.t(null, T.NUDGE, 1)}")
        e.clock.now = e.clock.now.plus(Duration.ofMinutes(31))
        e.review.submit(e.pending())
        nudges().size shouldBe 2
        nudges().last() shouldBe "send $CHAT ${Texts.t(null, T.NUDGE, 3)}"
        e.groups.get(CHAT)!!.nudgedAt shouldBe e.clock.now
    }

    "deliverPending on /start" {
        val e = Env("deliver")
        e.group(-100, admin(1)); e.group(-200, admin(1), admin(2)); e.group(-300, admin(2))
        val a = e.pending(-100); val b = e.pending(-200); e.pending(-300)
        e.users.started(1, "en")
        e.review.deliverPending(1) shouldBe 2
        e.tg.sent.map { it.chatId } shouldBe listOf(1L, 1L)
        e.subs.reviewMessages(a).map { it.first } shouldBe listOf(1L)
        e.subs.reviewMessages(b).map { it.first } shouldBe listOf(1L)
        e.review.deliverPending(1) shouldBe 0
        e.tg.sent.size shouldBe 2
    }

    "unreachable submission renders without answers" {
        val e = Env("unreachable")
        e.group(CHAT, admin(1))
        val s = Submission(1, CHAT, APPLICANT, null, ann, null, Status.PENDING, null, null, Instant.EPOCH, Kind.JOIN)
        val text = e.review.renderReview(s, null, "en")
        text shouldContain Texts.t("en", T.REVIEW_HEADER, "Ann", "Club$CHAT")
        text shouldContain "@ann"
        text shouldContain Texts.t("en", T.REVIEW_UNREACHABLE, "Ann")
    }

    "a partial submission leads with the unreachable line, then the answered prompts" {
        val e = Env("partial")
        e.group(CHAT, admin(1))
        val s = Submission(1, CHAT, APPLICANT, 1, ann, mapOf("q1" to "fun"), Status.PENDING, null, null, Instant.EPOCH, Kind.JOIN)
        val text = e.review.renderReview(s, form, "en")
        text shouldContain Texts.t("en", T.REVIEW_UNREACHABLE, "Ann") + "\n\nWhy join?: fun"
        text shouldNotContain "Where from?"
    }

    "a full submission, skipped fields included, carries no unreachable line" {
        val e = Env("full")
        e.group(CHAT, admin(1))
        val s = Submission(1, CHAT, APPLICANT, 1, ann, mapOf("q1" to "fun", "q2" to ""), Status.PENDING, null, null, Instant.EPOCH, Kind.JOIN)
        val text = e.review.renderReview(s, form, "en")
        text shouldNotContain Texts.t("en", T.REVIEW_UNREACHABLE, "Ann")
        text shouldContain "Why join?: fun\nWhere from?: "
    }

    "long reviews are cut at a line boundary" {
        val e = Env("long")
        e.group(CHAT, admin(1))
        e.users.started(1, "en")
        val long = Form("hi", (1..10).map { Text("q$it", "Question $it") })
        e.forms.save(CHAT, long, 1, 9, e.clock.instant())
        val answers = (1..10).associate { "q$it" to "x".repeat(1000) }
        val id = e.subs.create(CHAT, APPLICANT, 2, ann, answers, Status.PENDING, e.clock.instant())
        e.review.submit(id)
        val text = e.tg.sent.single().text
        (text.length <= 4096) shouldBe true
        text shouldEndWith "\n…"
        text shouldContain "Question 1: "
        text shouldNotContain "Question 10:"
        e.review.onDecision(1, "c1", "r|$id|a")
        e.tg.edits.single().text.let { (it.length <= 4096) shouldBe true; it shouldEndWith Texts.t("en", T.DECIDED_BY_APPROVED, "A1") }
    }

    "suspended long review stays within 4096 in the owner's language, without buttons" {
        val e = Env("suspend long")
        e.group(CHAT, admin(1))
        e.users.started(1, "ru")
        val answers = mapOf("q1" to "x".repeat(3000), "q2" to "y".repeat(3000))
        val id = e.subs.create(CHAT, APPLICANT, 1, ann, answers, Status.PENDING, e.clock.instant())
        e.review.submit(id)
        e.review.suspendChat(CHAT)
        e.tg.edits.single().let {
            it.messageId shouldBe e.subs.reviewMessages(id).single().second
            (it.text.length <= 4096) shouldBe true
            it.text shouldEndWith "\n\n" + Texts.t("ru", T.REVIEW_SUSPENDED)
            it.buttons shouldBe emptyList()
        }
    }
})
