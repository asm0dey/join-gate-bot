package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.temporal.ChronoUnit

class GroupRegistryTest : StringSpec({
    "admin without invite: registered inactive, told on every update" {
        val db = testDb("registry invite"); val tg = FakeTg(); val groups = GroupRepo(db)
        val reg = GroupRegistry(groups, SessionRepo(db, testCrypto()), BotUserRepo(db), tg)
        reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false)
        reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false)
        groups.get(-100)!!.active shouldBe false
        val notice = -100L to Texts.t(null, T.NEEDS_INVITE_RIGHT)
        tg.sent.map { it.chatId to it.text } shouldBe listOf(notice, notice)
        reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        groups.get(-100)!!.active shouldBe true
        tg.sent.size shouldBe 2
    }

    "removal deactivates and tells open sessions" {
        val db = testDb("registry removal"); val tg = FakeTg(); val groups = GroupRepo(db)
        val sessions = SessionRepo(db, testCrypto())
        val reg = GroupRegistry(groups, sessions, BotUserRepo(db), tg)
        reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        groups.get(-100)!!.active shouldBe true
        val at = Instant.now().truncatedTo(ChronoUnit.MICROS)
        sessions.put(Session(1, -100, 1, 0, SessionState(Profile("A", "a")), "ru", at))
        sessions.put(Session(2, -100, 1, 0, SessionState(Profile("B", "b")), null, at))
        reg.onBotStatus(-100, "G", isAdmin = false, canInvite = false)
        groups.get(-100)!!.active shouldBe false
        sessions.get(1, -100) shouldBe null
        tg.sent.map { it.chatId to it.text }.toSet() shouldBe
            setOf(1L to Texts.t("ru", T.GROUP_GONE), 2L to Texts.t(null, T.GROUP_GONE))
    }

    "demoted but still admin: sessions closed, applicants and group told" {
        val db = testDb("registry demote"); val tg = FakeTg(); val groups = GroupRepo(db)
        val sessions = SessionRepo(db, testCrypto())
        val reg = GroupRegistry(groups, sessions, BotUserRepo(db), tg)
        reg.onBotStatus(-100, "G", isAdmin = true, canInvite = true)
        sessions.put(Session(1, -100, 1, 0, SessionState(Profile("A", "a")), null, Instant.now().truncatedTo(ChronoUnit.MICROS)))
        reg.onBotStatus(-100, "G", isAdmin = true, canInvite = false)
        sessions.get(1, -100) shouldBe null
        tg.sent.map { it.chatId to it.text }.toSet() shouldBe
            setOf(1L to Texts.t(null, T.GROUP_GONE), -100L to Texts.t(null, T.NEEDS_INVITE_RIGHT))
    }
})
