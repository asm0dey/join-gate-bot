package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class RegEnv(name: String) {
    val db = testDb(name)
    val clock = TestClock()
    val tg = FakeTg()
    val groups = GroupRepo(db)
    val forms = FormRepo(db)
    val sessions = SessionRepo(db, testCrypto())
    val subs = SubmissionRepo(db, testCrypto())
    val users = BotUserRepo(db)
    val review = ReviewService(subs, forms, groups, users, AdminCheck(tg, clock), tg, clock)
    val flow = ApplicantFlow(groups, forms, sessions, subs, users, review, tg, clock)
    val reg = GroupRegistry(groups, sessions, subs, users, review, flow, tg)

    /** Group -100 with decider 1 who started the bot. */
    fun group(active: Boolean) {
        groups.upsert(-100, "G", active)
        forms.save(-100, Form("hi", listOf(Text("q1", "Why?"))), 0, 9, clock.instant())
        tg.adminsOf[-100] = listOf(Admin(1, "Boss", false, true))
        users.started(1, "en")
    }

    fun pending(user: Long) = subs.create(-100, user, 1, Profile("U$user", null), mapOf("q1" to "fun"), Status.PENDING, clock.instant())
    fun at(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
}

class GroupRegistryTest : StringSpec({
    "admin without invite: registered inactive, told on every update" {
        val e = RegEnv("registry invite")
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false)
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false)
        e.groups.get(-100)!!.active shouldBe false
        val notice = -100L to Texts.t(null, T.NEEDS_INVITE_RIGHT)
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(notice, notice)
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        e.groups.get(-100)!!.active shouldBe true
        e.tg.sent.size shouldBe 2
    }

    "removal deactivates and tells open sessions" {
        val e = RegEnv("registry removal")
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        e.groups.get(-100)!!.active shouldBe true
        e.sessions.put(Session(1, -100, 1, 0, SessionState(Profile("A", "a")), "ru", e.at()))
        e.sessions.put(Session(2, -100, 1, 0, SessionState(Profile("B", "b")), null, e.at()))
        e.reg.onBotStatus(-100, "G", isAdmin = false, canInvite = false)
        e.groups.get(-100)!!.active shouldBe false
        e.sessions.get(1, -100) shouldBe null
        e.tg.sent.map { it.chatId to it.text }.toSet() shouldBe
            setOf(1L to Texts.t("ru", T.GROUP_GONE), 2L to Texts.t(null, T.GROUP_GONE))
    }

    "demoted but still admin: sessions closed, applicants and group told" {
        val e = RegEnv("registry demote")
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        e.sessions.put(Session(1, -100, 1, 0, SessionState(Profile("A", "a")), null, e.at()))
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false)
        e.sessions.get(1, -100) shouldBe null
        e.tg.sent.map { it.chatId to it.text }.toSet() shouldBe
            setOf(1L to Texts.t(null, T.GROUP_GONE), -100L to Texts.t(null, T.NEEDS_INVITE_RIGHT))
    }

    "losing the right hands pending requests to manual review" {
        val e = RegEnv("registry handoff")
        e.group(active = true)
        e.users.started(5, "ru")
        val a = e.pending(5); val b = e.pending(6)
        e.review.submit(a); e.review.submit(b)
        val copies = (e.subs.reviewMessages(a) + e.subs.reviewMessages(b)).map { it.second }.toSet()
        e.sessions.put(Session(7, -100, 1, 0, SessionState(Profile("C", null)), null, e.at()))
        e.tg.sent.clear()
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false)
        e.tg.sent.map { it.chatId to it.text }.toSet() shouldBe setOf(
            5L to Texts.t("ru", T.MANUAL_REVIEW), 6L to Texts.t(null, T.MANUAL_REVIEW), 7L to Texts.t(null, T.GROUP_GONE),
            -100L to Texts.t(null, T.GROUP_HANDOFF, 2), -100L to Texts.t(null, T.NEEDS_INVITE_RIGHT),
        )
        e.tg.sent.size shouldBe 5
        e.tg.edits.map { it.messageId }.toSet() shouldBe copies
        e.tg.edits.size shouldBe 2
        e.tg.edits.forEach { it.text shouldEndWith "\n\n" + Texts.t("en", T.REVIEW_SUSPENDED); it.buttons shouldBe emptyList() }
        listOf(a, b).forEach { e.subs.get(it)!!.status shouldBe Status.PENDING }
    }

    "no pending → no handoff line" {
        val e = RegEnv("registry no pending")
        e.group(active = true)
        e.reg.onBotStatus(-100, "G", isAdmin = false, canInvite = false)
        e.tg.sent shouldBe emptyList()
        e.tg.edits shouldBe emptyList()
    }

    "already inactive → nothing re-sent" {
        val e = RegEnv("registry inactive")
        e.group(active = false)
        val id = e.pending(5)
        e.subs.addReviewMessage(id, 1, 77)
        e.reg.onBotStatus(-100, "G", isAdmin = false, canInvite = false)
        e.tg.sent shouldBe emptyList()
        e.tg.edits shouldBe emptyList()
    }

    "right restored → copies re-sent with buttons" {
        val e = RegEnv("registry restore")
        e.group(active = false)
        val id = e.pending(5)
        e.subs.addReviewMessage(id, 1, 77)
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        val m = e.tg.sent.single()
        m.chatId shouldBe 1L
        m.buttons.flatten().map { it.data } shouldBe listOf("r|$id|a", "r|$id|j")
        e.subs.reviewMessages(id) shouldBe listOf(1L to 101L)
        e.subs.get(id)!!.status shouldBe Status.PENDING
    }

    "decide after restore uses the new copy" {
        val e = RegEnv("registry restore decide")
        e.group(active = false)
        val id = e.pending(5)
        e.subs.addReviewMessage(id, 1, 77)
        e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        e.review.onDecision(1, "c1", "r|$id|a")
        e.subs.get(id)!!.status shouldBe Status.APPROVED
        e.tg.edits.single().messageId shouldBe 101L
    }
    "stored-inactive group with a session: closed with GROUP_GONE, no handoff" {
        val e = RegEnv("registry stale session")
        e.group(active = false)
        e.pending(5)
        e.sessions.put(Session(7, -100, 1, 0, SessionState(Profile("C", null)), null, e.at()))
        e.reg.onBotStatus(-100, "G", isAdmin = false, canInvite = false)
        e.sessions.get(7, -100) shouldBe null
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(7L to Texts.t(null, T.GROUP_GONE))
        e.tg.edits shouldBe emptyList()
    }

    "concurrent demote and restore leave copies matching the final state" {
        repeat(20) { i ->
            val e = RegEnv("registry race $i")
            e.group(active = true)
            val id = e.pending(5)
            e.review.submit(id)
            coroutineScope {
                launch(Dispatchers.Default) { e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false) }
                launch(Dispatchers.Default) { e.reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true) }
            }
            val copy = e.subs.reviewMessages(id).single().second
            val suspended = e.tg.edits.any { it.messageId == copy }
            suspended shouldBe !e.groups.get(-100)!!.active
        }
    }
})
