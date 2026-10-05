package joinbot

import eu.vendeli.tgbot.api.chat.getChat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.kotest.engine.test.TestResult
import kotlin.time.Duration

private val expectedArgs: Map<T, Array<Any>> = mapOf(
    T.NUDGE to arrayOf(3),
    T.GROUP_HANDOFF to arrayOf(2),
    T.DECIDED_BY_APPROVED to arrayOf("Ann"),
    T.DECIDED_BY_REJECTED to arrayOf("Ann"),
    T.ALREADY_DECIDED to arrayOf("Ann"),
    T.REVIEW_HEADER to arrayOf("Bob", "Group"),
    T.REVIEW_UNREACHABLE to arrayOf("Bob"),
    T.INVALID_TOO_FEW to arrayOf(2),
    T.INVALID_TOO_MANY to arrayOf(3),
    T.MULTI_EXACT to arrayOf(2),
    T.MULTI_RANGE to arrayOf(1, 3),
    T.MULTI_UP_TO to arrayOf(3),
)

class TelegramTest : StringSpec({
    "send is plain text with inline keyboard" {
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        bot.sendText(5, "<b>a_b*</b>", listOf(listOf(Button("Yes", "f|-100|0|p|0")))) shouldBe Sent.Ok(1)
        calls.single().run {
            path shouldBe "sendMessage"
            body shouldContain "\"<b>a_b*</b>\""
            body shouldNotContain "parse_mode"
            body shouldContain "f|-100|0|p|0"
        }
    }
    "403 maps to Forbidden" {
        failingBot(403, "Forbidden: bot was blocked by the user").sendText(5, "x") shouldBe Sent.Forbidden
    }
    "other failure maps to Failed" {
        failingBot(400, "Bad Request: chat not found").sendText(5, "x") shouldBe Sent.Failed
    }
    "approve maps errors" {
        listOf(
            "Bad Request: HIDE_REQUESTER_MISSING" to Decision.GONE,
            "Bad Request: USER_ALREADY_PARTICIPANT" to Decision.GONE,
            "Internal Server Error" to Decision.TRANSIENT,
        ).forEach { (d, r) ->
            failingBot(400, d).approveJoin(-100, 5) shouldBe r
            failingBot(400, d).declineJoin(-100, 5) shouldBe r
        }
    }
    "approve and decline succeed" {
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        bot.approveJoin(-100, 5) shouldBe Decision.OK
        bot.declineJoin(-100, 5) shouldBe Decision.OK
        calls.map { it.path } shouldBe listOf("approveChatJoinRequest", "declineChatJoinRequest")
    }
    "edit, answer and document go out" {
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        bot.editText(5, 12, "t")
        bot.answerCallback("cb1", "hi", alert = true)
        bot.sendFile(5, "x.csv", "a,b".toByteArray()) shouldBe Sent.Ok(1)
        calls.map { it.path } shouldBe listOf("editMessageText", "answerCallbackQuery", "sendDocument")
        calls[1].body shouldContain "\"show_alert\":true"
    }
    "keyboard is exactly one array per row" {
        val calls = mutableListOf<Call>()
        recordingBot(calls).sendText(5, "x", listOf(listOf(Button("A", "a"), Button("B", "b")), listOf(Button("C", "c"))))
        calls.single().body shouldContain """"inline_keyboard":[[{"text":"A","callback_data":"a"},{"text":"B","callback_data":"b"}],[{"text":"C","callback_data":"c"}]]"""
    }
    "answer sends no chat id" {
        val calls = mutableListOf<Call>()
        recordingBot(calls).answerCallback("cb1", "hi")
        calls.single().body shouldNotContain "chat_id"
        calls.single().body shouldContain "callback_query_id"
    }
    "failed admins lookup is null" {
        failingBot(400, "Bad Request").chatAdmins(-100) shouldBe null
    }
    "a fault in the fake Telegram fails the test" {
        val fake = FakeTelegram()
        runCatching { getChat().sendReturning(5, fake.bot).await() }
        fake.faults.single().message shouldContain "unexpected method getChat"
        FakeTelegramFaults.check(TestResult.Success(Duration.ZERO), FakeTelegram.drainFaults()).isFailure shouldBe true
    }
    "every T has en and ru" {
        T.entries.forEach {
            val a = expectedArgs[it] ?: emptyArray()
            Texts.t("ru", it, *a).shouldNotBeBlank(); Texts.t(null, it, *a).shouldNotBeBlank()
        }
    }
    "every T formats with its expected args" {
        T.entries.forEach { k ->
            val a = expectedArgs[k] ?: emptyArray()
            Texts.t("ru", k, *a).shouldNotBeBlank()
            Texts.t("en", k, *a).shouldNotContain("%")
        }
    }
    "forgotten args throw instead of leaking the template" {
        shouldThrow<java.util.MissingFormatArgumentException> { Texts.t(null, T.NUDGE) }
    }
    "every Reason has a text" { Reason.entries.forEach { Texts.t(null, it.text(), *(expectedArgs[it.text()] ?: emptyArray())).shouldNotBeBlank() } }

    "removeMember bans then unbans a member" {
        val fake = FakeTelegram().apply { members[-100L to 5L] = "member" }
        fake.bot.removeMember(-100, 5) shouldBe Kick.OK
        fake.calls.filter { it.contains("ban") } shouldBe listOf("ban -100 5", "unban -100 5")
    }
    "removeMember on someone who left is GONE" {
        val fake = FakeTelegram()
        fake.bot.removeMember(-100, 5) shouldBe Kick.GONE
        fake.calls.none { it.contains("ban") } shouldBe true
    }
    "removeMember without the right is NO_RIGHT" {
        val fake = FakeTelegram().apply { members[-100L to 5L] = "member"; banResult = Kick.NO_RIGHT }
        fake.bot.removeMember(-100, 5) shouldBe Kick.NO_RIGHT
    }
    "removeMember transient ban failure is TRANSIENT" {
        val fake = FakeTelegram().apply { members[-100L to 5L] = "member"; banResult = Kick.TRANSIENT }
        fake.bot.removeMember(-100, 5) shouldBe Kick.TRANSIENT
    }
    "memberInfo maps statuses" {
        val fake = FakeTelegram().apply {
            members[-100L to 1L] = "restricted"; members[-100L to 2L] = "creator"; members[-100L to 3L] = "kicked"
            members[-100L to 4L] = "administrator"; members[-100L to 5L] = "member"; members[-100L to 6L] = "left"
        }
        val want = Profile("User", "user")
        fake.bot.memberInfo(-100, 1) shouldBe MemberInfo(Membership.MEMBER, want)
        fake.bot.memberInfo(-100, 2) shouldBe MemberInfo(Membership.ADMIN, want)
        fake.bot.memberInfo(-100, 3) shouldBe MemberInfo(Membership.GONE, want)
        fake.bot.memberInfo(-100, 4) shouldBe MemberInfo(Membership.ADMIN, want)
        fake.bot.memberInfo(-100, 5) shouldBe MemberInfo(Membership.MEMBER, want)
        fake.bot.memberInfo(-100, 6) shouldBe MemberInfo(Membership.GONE, want)
    }
    "memberCount and botUsername" {
        val fake = FakeTelegram().apply { memberCount = 42 }
        fake.bot.memberCount(-100) shouldBe 42
        fake.bot.botUsername() shouldBe "fakebot"
        FakeTelegram().bot.memberCount(-100) shouldBe null
    }
})
