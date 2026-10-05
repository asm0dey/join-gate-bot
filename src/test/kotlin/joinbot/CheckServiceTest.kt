package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val CHAT = -100L
private const val CHAT2 = -200L
private const val U = 5L
private const val ADMIN = 1L
private const val BOT = 42L
private val ann = Profile("Ann", "ann")

private class CheckEnv(name: String) {
    val db = testDb(name)
    val clock = TestClock()
    val tg = FakeTelegram()
    val groups = GroupRepo(db)
    val forms = FormRepo(db)
    val sessions = SessionRepo(db, testCrypto())
    val subs = SubmissionRepo(db, testCrypto())
    val users = BotUserRepo(db)
    val members = MemberRepo(db)
    val checks = CheckRepo(db)
    val admins = AdminCheck(tg.bot, clock, BOT)
    val review = ReviewService(subs, forms, groups, users, members, admins, tg.bot, clock)
    val flow = ApplicantFlow(groups, forms, sessions, subs, users, review, tg.bot, clock)
    val svc = CheckService(groups, forms, subs, members, checks, sessions, users, admins, flow, Roster(members), tg.bot, clock)

    init { group(CHAT, "Club") }

    fun group(chat: Long, title: String, form: Boolean = true) {
        groups.upsert(chat, title, true)
        if (form) forms.save(chat, Form("Hi", listOf(Text("q1", "Why?"))), 0, 9, clock.instant())
        tg.adminsOf[chat] = listOf(Admin(ADMIN, "Boss", false, true, true), Admin(BOT, "Bot", true, true, true))
    }

    fun texts() = tg.sent.map { it.chatId to it.text }

    /** A check that opened and was due at the start, with two group posts; the clock is an hour past it. */
    fun dueCheck() {
        checks.open(CHAT, clock.instant(), ADMIN, clock.instant())
        checks.addMessage(CHAT, 11); checks.addMessage(CHAT, 12)
        clock.now = clock.now.plus(Duration.ofHours(1))
    }

    fun member(userId: Long, status: String = "member", profile: Profile? = null) {
        members.seen(CHAT, userId)
        tg.members[CHAT to userId] = status
        if (profile != null) tg.profiles[userId] = profile
    }
}

private const val DEADLINE = "2026-01-01 00:00 UTC"
private fun header(known: Int, total: Int) = Texts.t(null, T.NONRESPONDERS, "Club", DEADLINE, known, total)
private fun removeButton(label: String, userId: Long) = listOf(Button(Texts.t(null, T.REMOVE_NAME, label), "k|$CHAT|$userId"))

class CheckServiceTest : StringSpec({
    "parseDuration bounds" {
        val svc = CheckEnv("cs-parse").svc
        svc.parseDuration("1h") shouldBe Duration.ofHours(1)
        svc.parseDuration("365d") shouldBe Duration.ofDays(365)
        svc.parseDuration("48h") shouldBe Duration.ofHours(48)
        svc.parseDuration(null) shouldBe Duration.ofDays(7)
        for (bad in listOf("0h", "366d", "8761h", "7w", "", "d", "99999999999999999999d")) svc.parseDuration(bad).shouldBeNull()
    }

    "only check deciders can remind" {
        val e = CheckEnv("cs-deciders")
        e.tg.adminsOf[CHAT] = listOf(Admin(ADMIN, "Boss", false, true, false), Admin(BOT, "Bot", true, true, true))
        e.svc.remind(CHAT, ADMIN, false, null)
        e.tg.calls.shouldBeEmpty()
        e.checks.get(CHAT).shouldBeNull()
    }

    "remind refuses without a form or without the ban right" {
        val e = CheckEnv("cs-refuse")
        e.svc.remind(CHAT, ADMIN, false, "0h")
        e.group(CHAT2, "Empty", form = false)
        e.svc.remind(CHAT2, ADMIN, false, null)
        e.tg.adminsOf[CHAT] = listOf(Admin(ADMIN, "Boss", false, true, true), Admin(BOT, "Bot", true, true, false))
        e.svc.remind(CHAT, ADMIN, false, null)
        e.texts() shouldBe listOf(
            CHAT to Texts.t(null, T.REMIND_BAD_DURATION),
            CHAT2 to Texts.t(null, T.REMIND_NO_FORM),
            CHAT to Texts.t(null, T.REMIND_NO_BAN),
        )
        e.checks.get(CHAT).shouldBeNull()
        e.checks.get(CHAT2).shouldBeNull()
    }

    "remind posts the deep link and tells the admin the coverage" {
        val e = CheckEnv("cs-post")
        e.members.seen(CHAT, 7); e.members.seen(CHAT, 8)
        e.tg.memberCount = 10
        e.users.started(ADMIN, "en")
        e.svc.remind(CHAT, ADMIN, false, null)
        val post = e.tg.sent[0]
        post.chatId shouldBe CHAT
        post.text shouldBe Texts.t(null, T.REMIND_POST, "2026-01-08 00:00 UTC")
        post.buttons shouldBe listOf(listOf(Button(Texts.t(null, T.FILL_FORM), "https://t.me/joinbot?start=r-100")))
        e.tg.sent[1].let { it.chatId to it.text } shouldBe (ADMIN to Texts.t("en", T.REMIND_KNOWS, 2, 10, "Club"))
        val check = e.checks.openCheck(CHAT)!!
        check.deadline shouldBe e.clock.instant().plus(Duration.ofDays(7))
        check.startedBy shouldBe ADMIN
        e.checks.messages(CHAT) shouldBe listOf(101L)
    }

    "a second remind re-posts and moves the deadline only with a duration" {
        val e = CheckEnv("cs-again")
        e.svc.remind(CHAT, ADMIN, false, "2d")
        val first = e.checks.openCheck(CHAT)!!.deadline
        e.clock.now = e.clock.now.plus(Duration.ofHours(1))
        e.svc.remind(CHAT, ADMIN, false, null)
        e.checks.openCheck(CHAT)!!.deadline shouldBe first
        e.tg.sent.filter { it.chatId == CHAT }.map { it.text }.toSet() shouldBe setOf(Texts.t(null, T.REMIND_POST, "2026-01-03 00:00 UTC"))
        e.svc.remind(CHAT, ADMIN, false, "1d")
        e.checks.openCheck(CHAT)!!.deadline shouldBe e.clock.instant().plus(Duration.ofDays(1))
        e.checks.openCheck(CHAT)!!.startedAt shouldBe first.minus(Duration.ofDays(2)) // still the first check
        e.checks.messages(CHAT).size shouldBe 3
    }

    "remind posts nothing stored when the post fails" {
        val e = CheckEnv("cs-fail")
        e.tg.sendResultByChat[CHAT] = Sent.Failed
        e.svc.remind(CHAT, ADMIN, false, null)
        e.checks.get(CHAT).shouldBeNull()
        e.texts().last() shouldBe (ADMIN to Texts.t(null, T.REMIND_POST_FAILED, "Club"))
    }

    "enter: ended" {
        val e = CheckEnv("cs-enter-ended")
        e.svc.enter(CHAT, ann, U, "en")
        e.texts() shouldBe listOf(U to Texts.t("en", T.CHECK_ENDED))
        e.checks.open(CHAT, e.clock.instant(), ADMIN, e.clock.instant())
        e.checks.close(CHAT, e.clock.instant())
        e.svc.enter(CHAT, ann, U, "en")
        e.texts().last() shouldBe (U to Texts.t("en", T.CHECK_ENDED))
    }

    "enter: not a member" {
        val e = CheckEnv("cs-enter-gone")
        e.checks.open(CHAT, e.clock.instant().plus(Duration.ofDays(1)), ADMIN, e.clock.instant())
        e.svc.enter(CHAT, ann, U, "en")
        e.texts() shouldBe listOf(U to Texts.t("en", T.NOT_IN_GROUP))
        e.members.known(CHAT, U) shouldBe false
    }

    "enter: admin" {
        val e = CheckEnv("cs-enter-admin")
        e.checks.open(CHAT, e.clock.instant().plus(Duration.ofDays(1)), ADMIN, e.clock.instant())
        e.tg.members[CHAT to U] = "administrator"
        e.svc.enter(CHAT, ann, U, "en")
        e.texts() shouldBe listOf(U to Texts.t("en", T.ADMINS_EXEMPT))
    }

    "enter: pending" {
        val e = CheckEnv("cs-enter-pending")
        e.checks.open(CHAT, e.clock.instant().plus(Duration.ofDays(1)), ADMIN, e.clock.instant())
        e.tg.members[CHAT to U] = "member"
        e.subs.create(CHAT, U, 1, ann, mapOf("q1" to "x"), Status.PENDING, e.clock.instant(), Kind.CHECK)
        e.svc.enter(CHAT, ann, U, "en")
        e.texts() shouldBe listOf(U to Texts.t("en", T.CHECK_PENDING))
        e.members.known(CHAT, U) shouldBe true
    }

    "enter: passed → update offer" {
        val e = CheckEnv("cs-enter-passed")
        e.checks.open(CHAT, e.clock.instant().plus(Duration.ofDays(1)), ADMIN, e.clock.instant())
        e.tg.members[CHAT to U] = "member"
        e.members.pass(CHAT, U, e.clock.instant())
        e.svc.enter(CHAT, ann, U, "en")
        e.tg.sent.single() shouldBe SentMsg(U, Texts.t("en", T.OFFER_UPDATE, "Club"), listOf(listOf(Button(Texts.t("en", T.YES_UPDATE), "u|$CHAT"))))
    }

    "enter: else a check session, and a second tap re-asks it" {
        val e = CheckEnv("cs-enter-start")
        e.checks.open(CHAT, e.clock.instant().plus(Duration.ofDays(1)), ADMIN, e.clock.instant())
        e.tg.members[CHAT to U] = "restricted"
        e.svc.enter(CHAT, ann, U, "en")
        e.texts() shouldBe listOf(U to Texts.t("en", T.FORM_FOR, "Club"), U to "Hi", U to "Why?")
        e.sessions.get(U, CHAT)!!.kind shouldBe Kind.CHECK
        e.members.known(CHAT, U) shouldBe true
        e.members.pass(CHAT, U, e.clock.instant()) // an open session wins over the update offer
        e.svc.enter(CHAT, ann, U, "en")
        e.texts().last() shouldBe (U to "Why?")
    }

    "update button starts an update session for a passed member; stale otherwise" {
        val e = CheckEnv("cs-update")
        e.tg.members[CHAT to U] = "member"
        e.svc.onUpdateButton(U, "cb", "u|$CHAT", ann, "en")
        e.tg.calls.last() shouldBe "answer cb ${Texts.t("en", T.STALE_BUTTON)} alert"
        e.members.pass(CHAT, U, e.clock.instant())
        e.svc.onUpdateButton(U, "cb", "u|x", ann, "en")
        e.tg.calls.last() shouldBe "answer cb ${Texts.t("en", T.STALE_BUTTON)} alert"
        e.tg.members[CHAT to U] = "left"
        e.svc.onUpdateButton(U, "cb", "u|$CHAT", ann, "en")
        e.tg.calls.last() shouldBe "answer cb ${Texts.t("en", T.STALE_BUTTON)} alert"
        e.sessions.get(U, CHAT).shouldBeNull()

        e.tg.members[CHAT to U] = "member"
        e.svc.onUpdateButton(U, "cb", "u|$CHAT", ann, "en")
        e.tg.calls.first { it.startsWith("answer") && !it.endsWith("alert") } shouldBe "answer cb "
        e.texts().takeLast(3) shouldBe listOf(U to Texts.t("en", T.FORM_FOR, "Club"), U to "Hi", U to "Why?")
        e.sessions.get(U, CHAT)!!.kind shouldBe Kind.UPDATE
    }

    "plain /start lists update buttons per passed group" {
        val e = CheckEnv("cs-offer")
        e.svc.offerUpdates(U, "en") shouldBe false
        e.group(CHAT2, "Alpha")
        e.group(-300, "NoForm", form = false)
        e.group(-400, "Gone"); e.groups.upsert(-400, "Gone", false)
        for (c in listOf(CHAT, CHAT2, -300L, -400L)) e.members.pass(c, U, e.clock.instant())
        e.svc.offerUpdates(U, "en") shouldBe true
        e.tg.sent.single() shouldBe SentMsg(
            U, Texts.t("en", T.UPDATE_LIST),
            listOf(listOf(Button("Alpha", "u|$CHAT2")), listOf(Button("Club", "u|$CHAT"))),
        )
    }

    "update button with a session for that chat is stale and starts nothing" {
        val e = CheckEnv("cs-update-session")
        e.tg.members[CHAT to U] = "member"
        e.checks.open(CHAT, e.clock.instant().plus(Duration.ofDays(1)), ADMIN, e.clock.instant())
        e.svc.enter(CHAT, ann, U, "en") // a Check session, now at "Why?"
        e.members.pass(CHAT, U, e.clock.instant())
        val before = e.tg.sent.size
        e.svc.onUpdateButton(U, "cb", "u|$CHAT", ann, "en")
        e.tg.calls.last() shouldBe "answer cb ${Texts.t("en", T.STALE_BUTTON)} alert"
        e.tg.sent.size shouldBe before
        e.sessions.get(U, CHAT)!!.kind shouldBe Kind.CHECK
    }

    "enter with a queued session for that chat says it is queued" {
        val e = CheckEnv("cs-enter-queued")
        e.group(CHAT2, "Alpha")
        for (c in listOf(CHAT, CHAT2)) {
            e.checks.open(c, e.clock.instant().plus(Duration.ofDays(1)), ADMIN, e.clock.instant())
            e.tg.members[c to U] = "member"
        }
        e.svc.enter(CHAT2, ann, U, "en")
        e.svc.enter(CHAT, ann, U, "en")
        e.sessions.get(U, CHAT)!!.step shouldBe WAITING
        e.svc.enter(CHAT, ann, U, "en")
        e.texts().takeLast(2) shouldBe List(2) { U to Texts.t("en", T.QUEUED, "Club") }
    }
    "the deadline closes the check, edits every post and lists non-responders" {
        val e = CheckEnv("cs-deadline")
        e.dueCheck()
        e.member(U, profile = Profile("Bob", "bob"))
        e.member(6); e.members.pass(CHAT, 6, e.clock.instant())
        e.member(8); e.subs.create(CHAT, 8, 1, ann, mapOf("q1" to "x"), Status.PENDING, e.clock.instant(), Kind.CHECK)
        e.tg.memberCount = 10
        e.svc.tick()
        e.checks.get(CHAT)!!.closedAt shouldBe e.clock.instant()
        val closed = Texts.t(null, T.REMIND_POST, DEADLINE) + "\n\n" + Texts.t(null, T.CHECK_CLOSED, "2026-01-01 01:00 UTC")
        e.tg.edits shouldBe listOf(EditMsg(CHAT, 11, closed, emptyList()), EditMsg(CHAT, 12, closed, emptyList()))
        e.tg.sent.single() shouldBe SentMsg(ADMIN, header(3, 10), listOf(removeButton("Bob @bob", U)))
        e.tg.sent.single().buttons.single().single().text shouldBe "Remove Bob @bob"
        e.checks.undelivered(ADMIN).shouldBeEmpty()
    }

    "leavers and admins are left off; a failed lookup is listed by id" {
        val e = CheckEnv("cs-deadline-skip")
        e.dueCheck()
        e.member(5, "left")
        e.member(6, "administrator")
        e.member(7); e.tg.failMemberFor += 7L
        e.tg.memberCount = 10
        e.svc.tick()
        e.tg.sent.single() shouldBe SentMsg(ADMIN, header(2, 10), listOf(removeButton(Texts.t(null, T.ID_ONLY, 7L), 7)))
        e.tg.sent.single().buttons.single().single().text shouldBe "Remove id 7"
        e.members.known(CHAT, 5) shouldBe false
        e.members.known(CHAT, 6) shouldBe true
    }

    "a second tick sends nothing" {
        val e = CheckEnv("cs-deadline-once")
        e.dueCheck()
        e.member(U)
        coroutineScope { repeat(2) { launch(Dispatchers.Default) { e.svc.tick() } } }
        e.svc.tick()
        e.tg.sent.size shouldBe 1
        e.tg.edits.size shouldBe 2
    }

    "more than 50 non-responders split into messages of 50 buttons" {
        val e = CheckEnv("cs-deadline-chunks")
        e.dueCheck()
        for (u in 1000L until 1120L) e.member(u)
        e.tg.memberCount = 200
        e.svc.tick()
        e.tg.sent.map { it.buttons.size } shouldBe listOf(50, 50, 20)
        e.tg.sent.forEach { m ->
            m.chatId shouldBe ADMIN
            m.text shouldBe header(120, 200)
            m.buttons.forEach { it.size shouldBe 1 }
        }
        e.tg.sent.flatMap { it.buttons.flatten() }.map { it.data } shouldBe (1000L until 1120L).map { "k|$CHAT|$it" }
    }

    "nobody left to list says everyone filled it" {
        val e = CheckEnv("cs-deadline-none")
        e.dueCheck()
        e.member(6); e.members.pass(CHAT, 6, e.clock.instant())
        e.svc.tick()
        e.tg.sent.single() shouldBe SentMsg(ADMIN, Texts.t(null, T.ALL_PASSED, "Club"), emptyList())
    }

    "Remove kicks, records REMOVED and drops the button" {
        val e = CheckEnv("cs-remove")
        e.member(U, profile = Profile("Bob", "bob"))
        val keyboard = listOf(removeButton("Bob @bob", U), removeButton("id 7", 7))
        e.svc.onRemoveButton(ADMIN, "cb", "k|$CHAT|$U", 900, "list", keyboard)
        e.tg.calls.filter { it.startsWith("ban") || it.startsWith("answer") } shouldBe listOf("ban $CHAT $U", "answer cb ")
        e.members.known(CHAT, U) shouldBe false
        val s = e.subs.list(CHAT, null).single()
        s.status shouldBe Status.REMOVED
        s.kind shouldBe Kind.CHECK
        s.decidedBy shouldBe ADMIN
        s.decidedAt shouldBe e.clock.instant()
        s.profile shouldBe Profile("Bob", "bob")
        s.answers.shouldBeNull()
        s.formVersion.shouldBeNull()
        e.tg.edits.single() shouldBe EditMsg(ADMIN, 900, "list", listOf(removeButton("id 7", 7)))
    }

    "Remove refuses passed, pending and already removed" {
        val e = CheckEnv("cs-remove-refuse")
        suspend fun click(userId: Long, admin: Long = ADMIN) = e.svc.onRemoveButton(admin, "cb", "k|$CHAT|$userId", 900, "list", emptyList())
        fun alert(key: T, vararg args: Any) = "answer cb ${Texts.t(null, key, *args)} alert"
        e.member(U, profile = Profile("Bob", "bob")); e.members.pass(CHAT, U, e.clock.instant())
        click(U)
        e.tg.calls.last() shouldBe alert(T.PASSED_SINCE, "Bob")
        e.member(8, profile = Profile("Cy", null)); e.subs.create(CHAT, 8, 1, ann, null, Status.PENDING, e.clock.instant(), Kind.CHECK)
        click(8)
        e.tg.calls.last() shouldBe alert(T.PENDING_SINCE, "Cy")
        click(9)
        e.tg.calls.last() shouldBe alert(T.ALREADY_REMOVED)
        e.member(10)
        click(10, admin = 3)
        e.tg.calls.last() shouldBe alert(T.NOT_ADMIN_ANYMORE)
        e.tg.banResult = Kick.NO_RIGHT
        click(10)
        e.tg.calls.last() shouldBe alert(T.NO_BAN_RIGHT)
        e.tg.banResult = Kick.TRANSIENT
        click(10)
        e.tg.calls.last() shouldBe alert(T.TRY_AGAIN)
        e.members.known(CHAT, 10) shouldBe true
        e.subs.list(CHAT, Status.REMOVED).shouldBeEmpty()
        e.tg.edits.shouldBeEmpty()
    }

    "an admin who blocked the bot gets the list on /start" {
        val e = CheckEnv("cs-deadline-blocked")
        e.dueCheck()
        e.member(U, profile = Profile("Bob", null))
        e.tg.memberCount = 4
        e.tg.sendResultByChat[ADMIN] = Sent.Forbidden
        e.svc.tick()
        e.checks.undelivered(ADMIN) shouldBe listOf(CHAT)
        e.users.dmOk(ADMIN) shouldBe false
        e.tg.sendResultByChat.clear()
        e.users.started(ADMIN, null)
        e.svc.deliverNotices(ADMIN) shouldBe 1
        e.tg.sent.last() shouldBe SentMsg(ADMIN, header(1, 4), listOf(removeButton("Bob", U)))
        e.checks.undelivered(ADMIN).shouldBeEmpty()
        e.svc.deliverNotices(ADMIN) shouldBe 0
    }
    "a failed admin lookup leaves the check open for the next tick" {
        val e = CheckEnv("cs-deadline-lookup")
        e.dueCheck()
        e.member(U)
        val admins = e.tg.adminsOf.remove(CHAT)!!
        e.svc.tick()
        e.checks.openCheck(CHAT) shouldBe e.checks.get(CHAT)
        e.checks.get(CHAT)!!.closedAt.shouldBeNull()
        e.tg.sent.shouldBeEmpty()
        e.tg.edits.shouldBeEmpty()
        e.tg.adminsOf[CHAT] = admins
        e.svc.tick()
        e.tg.sent.single().buttons.single().single().data shouldBe "k|$CHAT|$U"
        e.tg.edits.size shouldBe 2
    }

    "an inactive group's due check closes without a list" {
        val e = CheckEnv("cs-deadline-inactive")
        e.dueCheck()
        e.member(U)
        e.groups.upsert(CHAT, "Club", false)
        e.svc.tick()
        e.checks.get(CHAT)!!.closedAt shouldBe e.clock.instant()
        e.tg.edits.map { it.messageId } shouldBe listOf(11L, 12L)
        e.tg.sent.shouldBeEmpty()
    }

    "two Remove clicks for the same person record one removal" {
        val e = CheckEnv("cs-remove-twice")
        e.member(U)
        coroutineScope {
            repeat(2) { launch(Dispatchers.Default) { e.svc.onRemoveButton(ADMIN, "cb", "k|$CHAT|$U", 900, "list", emptyList()) } }
        }
        e.subs.list(CHAT, Status.REMOVED).size shouldBe 1
        e.tg.calls.filter { it.startsWith("answer") }.toSet() shouldBe setOf("answer cb ", "answer cb ${Texts.t(null, T.ALREADY_REMOVED)} alert")
        e.tg.edits.size shouldBe 1
    }
})
