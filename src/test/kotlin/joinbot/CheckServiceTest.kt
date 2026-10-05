package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

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
}

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
})
