package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val CHAT = -100L
private const val CHAT2 = -200L
private const val U = 5L
private const val U_CHAT = 55L
private const val ADMIN = 1L
private const val MSG = 900L
private val ann = Profile("Ann", "ann")

// spec §2 example
private val form = Form("Hi! A few questions before you join", listOf(
    Radio("a1", "Where do you live?", listOf("Limassol", "Nicosia"), other = true),
    Radio("a2", "Agree to the rules?", listOf("Yes", "No")),
    Multi("a3", "Interests?", listOf("Music", "Sport", "Books"), min = 1, max = 3),
    Text("a4", "About you", maxLen = 300),
    IntField("a5", "Age?", min = 18, max = 120),
    Link("a6", "LinkedIn?", required = false),
    Consent("a7", "Privacy terms… Do you agree?"),
))

private class FlowEnv(name: String) {
    val db = testDb(name)
    val clock = TestClock()
    val tg = FakeTelegram()
    val groups = GroupRepo(db)
    val forms = FormRepo(db)
    val sessions = SessionRepo(db, testCrypto())
    val subs = SubmissionRepo(db, testCrypto())
    val users = BotUserRepo(db)
    val review = ReviewService(subs, forms, groups, users, MemberRepo(db), AdminCheck(tg.bot, clock, 0), tg.bot, clock)
    val flow = ApplicantFlow(groups, forms, sessions, subs, users, review, tg.bot, clock)

    init { group(CHAT); users.started(ADMIN, "en") }

    fun group(chat: Long, f: Form = form) {
        groups.upsert(chat, "Club$chat", true)
        forms.save(chat, f, 0, 9, clock.instant())
        tg.adminsOf[chat] = listOf(Admin(ADMIN, "Boss", false, true))
    }

    suspend fun join(chat: Long = CHAT) = flow.onJoinRequest(chat, U, U_CHAT, ann, "en")
    fun session(chat: Long = CHAT) = sessions.get(U, chat)
    suspend fun press(action: Char, idx: Int = 0, chat: Long = CHAT, step: Int = session(chat)!!.step) =
        flow.onCallback(U, "cb", cbData(chat, step, action, idx), "en", MSG)
    suspend fun say(text: String?) = flow.onMessage(U, text, "en")
    fun toUser() = tg.sent.filter { it.chatId == U || it.chatId == U_CHAT }
    fun last() = toUser().last()
    fun stale() = tg.calls.count { it == "answer cb ${Texts.t("en", T.STALE_BUTTON)} alert" }

    /** Answers the example form from the current step up to (not including) [step]. */
    suspend fun fillTo(step: Int, chat: Long = CHAT) {
        val script: List<suspend () -> Unit> = listOf(
            { press('p', 0, chat) }, { press('p', 0, chat) }, { press('t', 0, chat); press('d', 0, chat) },
            { say("I like cats") }, { say("42") }, { press('s', 0, chat) }, { press('y', 0, chat) },
        )
        val from = session(chat)!!.step
        script.subList(from, step).forEach { it() }
    }
}

private fun en(key: T) = Texts.t("en", key)

class ApplicantFlowTest : StringSpec({
    "join request DMs welcome and question 1" {
        val e = FlowEnv("af-join")
        e.join()
        e.tg.sent.map { it.chatId } shouldBe listOf(U_CHAT, U_CHAT)
        e.tg.sent[0].text shouldBe "${form.welcome}\n\n${en(T.WELCOME_SKIP)}"
        e.tg.sent[1].text shouldBe "Where do you live?"
        e.tg.sent[1].buttons.flatten().map { it.text } shouldBe listOf("Limassol", "Nicosia", en(T.OTHER))
        e.tg.sent[1].buttons.flatten().map { it.data } shouldBe listOf("f|$CHAT|0|p|0", "f|$CHAT|0|p|1", "f|$CHAT|0|o|0")
        e.session()!!.step shouldBe 0
    }

    "a legacy blank welcome is not sent" {
        val e = FlowEnv("af-blank-welcome")
        e.group(CHAT2, Form(" ", listOf(Text("x1", "One"))))
        e.flow.onJoinRequest(CHAT2, U, U_CHAT, ann, "en")
        e.tg.sent.map { it.text } shouldBe listOf("One")
        e.session(CHAT2)!!.step shouldBe 0
    }

    "a legacy blank welcome with optional fields sends only the skip hint" {
        val e = FlowEnv("af-blank-welcome-skip")
        e.group(CHAT2, Form("", listOf(Text("x1", "One", required = false))))
        e.flow.onJoinRequest(CHAT2, U, U_CHAT, ann, "en")
        e.tg.sent.map { it.text } shouldBe listOf(en(T.WELCOME_SKIP), "One")
    }

    "no active group or no form → ignored" {
        val e = FlowEnv("af-ignore")
        e.groups.upsert(CHAT2, "NoForm", true)
        e.flow.onJoinRequest(CHAT2, U, U_CHAT, ann, "en")
        e.groups.upsert(CHAT, "Club", false)
        e.join()
        e.tg.sent.shouldBeEmpty()
        e.sessions.forUser(U).shouldBeEmpty()
    }

    "scripted run to submit stores a sealed submission" {
        val e = FlowEnv("af-run")
        e.join()
        e.fillTo(7)
        e.session()!!.step shouldBe 7
        e.last().buttons.flatten().map { it.data } shouldBe listOf("f|$CHAT|7|S|0", "f|$CHAT|7|R|0")
        e.last().text shouldBe listOf(en(T.SUMMARY_HEADER), "Where do you live?: Limassol", "Agree to the rules?: Yes",
            "Interests?: Music", "About you: I like cats", "Age?: 42", "LinkedIn?: —", "Privacy terms… Do you agree?: ✓").joinToString("\n")
        e.press('S')
        val s = e.subs.list(CHAT, Status.PENDING).single()
        s.answers shouldBe mapOf("a1" to "Limassol", "a2" to "Yes", "a3" to "Music", "a4" to "I like cats", "a5" to "42", "a6" to "", "a7" to "✓")
        s.profile shouldBe ann
        s.formVersion shouldBe 1
        e.session().shouldBeNull()
        e.toUser().last().text shouldBe en(T.SUBMITTED)
        e.tg.sent.any { m -> m.chatId == ADMIN && m.buttons.flatten().any { it.data == "r|${s.id}|a" } } shouldBe true
    }

    "button answers remove the question keyboard, multi toggles show ✓" {
        val e = FlowEnv("af-kb")
        e.join()
        e.press('p', 1)
        e.tg.edits.last() shouldBe EditMsg(U, MSG, "Where do you live?", emptyList())
        e.fillTo(2)
        e.press('t', 0); e.press('t', 2)
        e.tg.edits.last().buttons.flatten().map { it.text } shouldBe listOf("✓ Music", "Sport", "✓ Books", en(T.DONE))
        e.press('t', 0)
        e.tg.edits.last().buttons.flatten().map { it.text } shouldBe listOf("Music", "Sport", "✓ Books", en(T.DONE))
        e.session()!!.state.picks shouldBe setOf(2)
        e.press('d')
        e.session()!!.state.answers["a3"] shouldBe "Books"
        e.tg.edits.last() shouldBe EditMsg(U, MSG, "Interests?", emptyList())
    }

    "multi done with nothing picked alerts and keeps the step" {
        val e = FlowEnv("af-few")
        e.join(); e.fillTo(2)
        e.press('d')
        e.tg.calls.last() shouldBe "answer cb ${Texts.t("en", T.INVALID_TOO_FEW, 1)} alert"
        e.session()!!.step shouldBe 2
    }

    "invalid typed input re-asks with reason" {
        val e = FlowEnv("af-invalid")
        e.join(); e.fillTo(4)
        e.say("17") shouldBe true
        e.toUser().takeLast(2).map { it.text } shouldBe listOf(en(T.INVALID_TOO_SMALL), "Age?")
        e.session()!!.step shouldBe 4
    }

    "typed text to a button field re-asks with WRONG_KIND" {
        val e = FlowEnv("af-kind")
        e.join()
        e.say("Paphos") shouldBe true
        e.toUser().takeLast(2).map { it.text } shouldBe listOf(en(T.INVALID_WRONG_KIND), "Where do you live?")
        e.session()!!.step shouldBe 0
    }

    "other asks for text and stores it" {
        val e = FlowEnv("af-other")
        e.join()
        e.press('o')
        e.last().text shouldBe en(T.TYPE_OTHER)
        e.session()!!.state.otherMode shouldBe true
        e.say("Paphos")
        e.session()!!.state.answers shouldBe mapOf("a1" to "Paphos")
        e.session()!!.state.otherMode shouldBe false
        e.last().text shouldBe "Agree to the rules?"
    }

    "consent disagree declines and forgets" {
        val e = FlowEnv("af-consent")
        e.tg.failDeclineFor += U
        e.join(); e.fillTo(6)
        e.press('n')
        e.tg.calls.contains("decline $CHAT $U") shouldBe true
        e.session().shouldBeNull()
        e.subs.list(CHAT, null).shouldBeEmpty()
        e.last().text shouldBe en(T.DECLINED_CONSENT)
    }

    "start over resets to step 0" {
        val e = FlowEnv("af-restart")
        e.join(); e.fillTo(7)
        e.press('R')
        e.session()!!.step shouldBe 0
        e.session()!!.state.answers shouldBe emptyMap()
        e.toUser().takeLast(2).map { it.text } shouldBe listOf("${form.welcome}\n\n${en(T.WELCOME_SKIP)}", "Where do you live?")
    }

    "admin edit mid-flow does not shift questions" {
        val e = FlowEnv("af-pinned")
        e.join()
        e.forms.save(CHAT, form.copy(fields = listOf(form.fields[0], Text("b2", "New question?")) + form.fields.drop(1)), 1, 9, e.clock.instant())
        e.press('p', 0)
        e.last().text shouldBe "Agree to the rules?"
        e.session()!!.formVersion shouldBe 1
    }

    "second group waits, then starts after submit" {
        val e = FlowEnv("af-wait")
        e.group(CHAT2)
        e.join(CHAT)
        e.join(CHAT2)
        e.tg.sent.size shouldBe 2
        e.session(CHAT2)!!.step shouldBe WAITING
        e.fillTo(7)
        e.press('S')
        e.session(CHAT2)!!.step shouldBe 0
        e.toUser().takeLast(2).map { it.text } shouldBe listOf("${form.welcome}\n\n${en(T.WELCOME_SKIP)}", "Where do you live?")
        e.last().buttons.flatten().first().data shouldBe "f|$CHAT2|0|p|0"
    }

    "DM forbidden → unreachable submission to reviewers" {
        val e = FlowEnv("af-forbidden")
        e.tg.sendResultByChat[U_CHAT] = Sent.Forbidden
        e.join()
        val s = e.subs.list(CHAT, Status.PENDING).single()
        s.answers.shouldBeNull()
        s.profile shouldBe ann
        e.users.dmOk(U) shouldBe false
        e.session().shouldBeNull()
        e.tg.sent.any { m -> m.chatId == ADMIN && m.buttons.flatten().any { it.data == "r|${s.id}|a" } } shouldBe true
    }

    "forbidden mid-form → reviewers get the answers so far, the waiting session follows" {
        val e = FlowEnv("af-forbidden-mid")
        e.group(CHAT2)
        e.join(CHAT); e.join(CHAT2)
        e.fillTo(1)
        e.tg.sendResultByChat[U] = Sent.Forbidden
        e.press('p', 0)
        val s = e.subs.list(CHAT, Status.PENDING).single()
        s.answers shouldBe mapOf("a1" to "Limassol", "a2" to "Yes")
        e.users.dmOk(U) shouldBe false
        e.sessions.forUser(U).shouldBeEmpty()
        e.subs.list(CHAT2, Status.PENDING).single().answers.shouldBeNull()
        e.tg.sent.any { m -> m.chatId == ADMIN && m.buttons.flatten().any { it.data == "r|${s.id}|a" } } shouldBe true
    }

    "forbidden summary → reviewers get every answer" {
        val e = FlowEnv("af-forbidden-summary")
        e.join(); e.fillTo(6)
        e.tg.sendResultByChat[U] = Sent.Forbidden
        e.press('y')
        e.subs.list(CHAT, Status.PENDING).single().answers!!.keys shouldBe setOf("a1", "a2", "a3", "a4", "a5", "a6", "a7")
        e.session().shouldBeNull()
    }

    "a transient failure mid-form keeps the session" {
        val e = FlowEnv("af-failed-mid")
        e.join()
        e.tg.sendResultByChat[U] = Sent.Failed
        e.press('p', 0)
        e.session()!!.step shouldBe 1
        e.subs.list(CHAT, null).shouldBeEmpty()
    }

    "a transient failure on the first DM → unreachable submission, DM not marked closed" {
        val e = FlowEnv("af-failed-first")
        e.users.started(U, "en")
        e.tg.sendResultByChat[U_CHAT] = Sent.Failed
        e.join()
        e.subs.list(CHAT, Status.PENDING).single().answers.shouldBeNull()
        e.users.dmOk(U) shouldBe true
        e.session().shouldBeNull()
    }

    "a legacy blank welcome: a failed first question is the first contact" {
        val e = FlowEnv("af-blank-failed")
        e.group(CHAT2, Form("", listOf(Text("x1", "One"))))
        e.tg.sendResultByChat[U_CHAT] = Sent.Failed
        e.flow.onJoinRequest(CHAT2, U, U_CHAT, ann, "en")
        e.subs.list(CHAT2, Status.PENDING).size shouldBe 1
        e.session(CHAT2).shouldBeNull()
    }

    "a join request stores the applicant's language, leaving dm_ok alone" {
        val e = FlowEnv("af-lang")
        e.flow.onJoinRequest(CHAT, U, U_CHAT, ann, "ru")
        e.users.lang(U) shouldBe "ru"
        e.users.dmOk(U) shouldBe false
        e.users.started(U, "en")
        e.flow.onJoinRequest(CHAT, U, U_CHAT, ann, "ru")
        e.users.lang(U) shouldBe "ru"
        e.users.dmOk(U) shouldBe true
        e.flow.onJoinRequest(CHAT, U, U_CHAT, ann, null)
        e.users.lang(U) shouldBe "ru"
    }

    "the decision DM uses the language of the join request" {
        val e = FlowEnv("af-lang-decision")
        e.flow.onJoinRequest(CHAT, U, U_CHAT, ann, "ru")
        e.fillTo(7); e.press('S')
        val id = e.subs.list(CHAT, Status.PENDING).single().id
        e.review.onDecision(ADMIN, "c1", "r|$id|a")
        e.tg.sent.last().let { it.chatId shouldBe U; it.text shouldBe Texts.t("ru", T.APPROVED_USER) }
    }

    "/start resumes" {
        val e = FlowEnv("af-start")
        e.join(); e.fillTo(1)
        e.flow.onStart(U, "en") shouldBe true
        e.last().text shouldBe "Agree to the rules?"
        e.flow.onStart(42, "en") shouldBe false
    }

    "non-text message re-asks" {
        val e = FlowEnv("af-nontext")
        e.join(); e.fillTo(3)
        e.say(null) shouldBe true
        e.toUser().takeLast(2).map { it.text } shouldBe listOf(en(T.INVALID_WRONG_KIND), "About you")
        e.session()!!.step shouldBe 3
        e.flow.onMessage(42, "hi", "en") shouldBe false
    }

    "stale button changes nothing" {
        val e = FlowEnv("af-stale")
        e.join()
        e.press('p', 0)
        e.press('p', 1, step = 0)
        e.stale() shouldBe 1
        e.session()!!.state.answers shouldBe mapOf("a1" to "Limassol")
        e.session()!!.step shouldBe 1
        e.fillTo(7)
        e.press('R')
        e.press('S', step = 7)
        e.stale() shouldBe 2
        e.session()!!.step shouldBe 0
        e.fillTo(7)
        e.press('S')
        e.press('S', step = 7)
        e.stale() shouldBe 3
        e.subs.list(CHAT, null).size shouldBe 1
        e.flow.onCallback(U, "cb", "garbage", "en", MSG)
        e.stale() shouldBe 4
    }

    "callback data fits 64 bytes" {
        cbData(-1001234567890123, 49, 't', 19).toByteArray().size shouldBeLessThan 64
    }

    "long summary is split" {
        val e = FlowEnv("af-long")
        val long = Form("w", listOf(Text("x1", "One"), Text("x2", "Two"), Text("x3", "Three")))
        e.group(CHAT2, long)
        e.flow.onJoinRequest(CHAT2, U, U_CHAT, ann, "en")
        val before = e.toUser().size
        repeat(3) { e.say("x".repeat(4000)) }
        val summary = e.toUser().drop(before + 2) // two questions after the first answer
        summary.size shouldBeGreaterThanOrEqual 3
        summary.forEach { it.text.length shouldBeLessThanOrEqual SUMMARY_CHUNK }
        summary.dropLast(1).forEach { it.buttons.shouldBeEmpty() }
        summary.last().buttons.flatten().map { it.data } shouldBe listOf("f|$CHAT2|3|S|0", "f|$CHAT2|3|R|0")
        summary.joinToString("") { it.text }.count { it == 'x' } shouldBe 12000
    }

    "repeat join request" {
        val e = FlowEnv("af-repeat")
        e.join(); e.fillTo(2)
        e.join()
        e.session()!!.step shouldBe 0
        e.session()!!.state.answers shouldBe emptyMap()
        e.fillTo(7); e.press('S')
        val sent = e.tg.sent.size
        e.join()
        e.session().shouldBeNull()
        e.tg.sent.size shouldBe sent
    }

    "touched_at updates on accepted interaction" {
        val e = FlowEnv("af-touch")
        e.join()
        e.clock.now = e.clock.now.plusSeconds(60)
        e.press('p', 0)
        e.session()!!.touchedAt shouldBe e.clock.now
    }

    "double-tapped Submit creates one submission" {
        val e = FlowEnv("af-race-submit")
        e.join(); e.fillTo(7)
        coroutineScope {
            repeat(2) { launch(Dispatchers.Default) { e.press('S', step = 7) } }
        }
        e.subs.list(CHAT, null).size shouldBe 1
        e.stale() shouldBe 1
        e.flow.lockCount() shouldBe 0
    }

    "concurrent joins to two chats leave one active session" {
        val e = FlowEnv("af-race-join")
        e.group(CHAT2)
        coroutineScope {
            launch(Dispatchers.Default) { e.join(CHAT) }
            launch(Dispatchers.Default) { e.join(CHAT2) }
        }
        e.sessions.forUser(U).map { it.step }.sorted() shouldBe listOf(WAITING, 0)
        e.flow.lockCount() shouldBe 0
    }

    "submit after a create whose session delete failed does not resubmit" {
        val e = FlowEnv("af-resubmit")
        e.join(); e.fillTo(7)
        e.subs.create(CHAT, U, 1, ann, mapOf("a1" to "Limassol"), Status.PENDING, e.clock.instant())
        e.press('S')
        e.subs.list(CHAT, null).size shouldBe 1
        e.session().shouldBeNull()
    }

    "consent disagree starts the next waiting session" {
        val e = FlowEnv("af-decline-next")
        e.group(CHAT2)
        e.join(CHAT); e.join(CHAT2)
        e.fillTo(6)
        e.press('n')
        e.session(CHAT2)!!.step shouldBe 0
        e.last().buttons.flatten().first().data shouldBe "f|$CHAT2|0|p|0"
    }

    "closeForChat tells the user and starts their next waiting session" {
        val e = FlowEnv("af-close")
        e.group(CHAT2)
        e.join(CHAT); e.join(CHAT2)
        e.flow.closeForChat(CHAT)
        e.session(CHAT).shouldBeNull()
        e.session(CHAT2)!!.step shouldBe 0
        e.toUser().takeLast(3).map { it.text } shouldBe
            listOf(en(T.GROUP_GONE), "${form.welcome}\n\n${en(T.WELCOME_SKIP)}", "Where do you live?")
        e.flow.closeForChat(CHAT)
        e.session(CHAT2)!!.step shouldBe 0
    }

    "invalid input does not touch touched_at" {
        val e = FlowEnv("af-touch-invalid")
        e.join(); e.fillTo(4)
        val before = e.session()!!.touchedAt
        e.clock.now = e.clock.now.plusSeconds(60)
        e.say("17")
        e.session()!!.touchedAt shouldBe before
    }

    "summary hard cut never splits a surrogate pair" {
        val e = FlowEnv("af-emoji")
        e.group(CHAT2, Form("w", listOf(Text("x1", "One"))))
        e.flow.onJoinRequest(CHAT2, U, U_CHAT, ann, "en")
        val answer = "a".repeat(SUMMARY_CHUNK - 6) + "😀".repeat(250) // "One: " puts a high surrogate at the cut
        val before = e.toUser().size
        e.say(answer)
        val summary = e.toUser().drop(before)
        summary.size shouldBeGreaterThanOrEqual 3
        summary.forEach { it.text.last().isHighSurrogate() shouldBe false }
        summary.first().text shouldBe en(T.SUMMARY_HEADER)
        summary.drop(1).joinToString("") { it.text } shouldBe "One: $answer"
    }

    "a tap past At most is refused and says the limit" {
        val e = FlowEnv("af-multi-max")
        e.group(CHAT2, Form("Hi", listOf(Multi("m", "Pick", listOf("A", "B", "C"), max = 2))))
        e.join(CHAT2)
        e.press('t', 0, CHAT2); e.press('t', 1, CHAT2); e.press('t', 2, CHAT2)
        e.session(CHAT2)!!.state.picks shouldBe setOf(0, 1)
        e.tg.calls.last() shouldBe "answer cb Choose at most 2. alert"
        e.press('t', 0, CHAT2); e.press('t', 2, CHAT2) // untick one, and the third fits
        e.session(CHAT2)!!.state.picks shouldBe setOf(1, 2)
    }

    "Done with too few picks says the minimum" {
        val e = FlowEnv("af-multi-min")
        e.group(CHAT2, Form("Hi", listOf(Multi("m", "Pick", listOf("A", "B", "C"), min = 2))))
        e.join(CHAT2)
        e.press('t', 0, CHAT2); e.press('d', 0, CHAT2)
        e.tg.calls.last() shouldBe "answer cb Choose at least 2. alert"
        e.session(CHAT2)!!.step shouldBe 0
    }

    "a multiple-choice question says how many to choose, and keeps saying it while ticking" {
        val e = FlowEnv("af-multi-hint")
        e.group(CHAT2, Form("Hi", listOf(
            Multi("m", "Pick", listOf("A", "B", "C"), max = 2),
            Multi("x", "Exactly", listOf("A", "B", "C"), min = 2, max = 2),
            Multi("o", "Optional", listOf("A", "B", "C"), required = false),
        )))
        e.join(CHAT2)
        e.last().text shouldBe "Pick\n\nChoose 1 to 2."
        e.press('t', 0, CHAT2)
        e.tg.calls.last() shouldStartWith "edit $U "
        e.tg.calls.last() shouldEndWith "Pick\n\nChoose 1 to 2."
        e.press('d', 0, CHAT2)
        e.last().text shouldBe "Exactly\n\nChoose 2."
        e.press('t', 0, CHAT2); e.press('t', 1, CHAT2); e.press('d', 0, CHAT2)
        e.last().text shouldBe "Optional\n\nChoose up to 3."
    }

    "an optional multiple choice is At least 0: no Skip button, Done with nothing skips it" {
        val e = FlowEnv("af-multi-optional")
        // min = 1 left over from when the question was required: optional wins
        e.group(CHAT2, Form("Hi", listOf(Multi("o", "Optional", listOf("A", "B", "C"), min = 1, required = false), Link("l", "Site"))))
        e.join(CHAT2)
        e.last().text shouldBe "Optional\n\nChoose up to 3."
        e.last().buttons.flatten().map { it.text } shouldBe listOf("A", "B", "C", en(T.DONE))
        e.press('d', 0, CHAT2)
        e.session(CHAT2)!!.step shouldBe 1
    }
})
