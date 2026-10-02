package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain

private val expectedArgs: Map<T, Array<Any>> = mapOf(
    T.NUDGE to arrayOf(3),
    T.DECIDED_BY_APPROVED to arrayOf("Ann"),
    T.DECIDED_BY_REJECTED to arrayOf("Ann"),
    T.ALREADY_DECIDED to arrayOf("Ann"),
    T.REVIEW_HEADER to arrayOf("Bob", "Group"),
    T.REVIEW_UNREACHABLE to arrayOf("Bob"),
)

class VendeliTgTest : StringSpec({
    "send is plain text with inline keyboard" {
        val calls = mutableListOf<Call>()
        val tg = VendeliTg(recordingBot(calls))
        tg.send(5, "<b>a_b*</b>", listOf(listOf(Button("Yes", "f|-100|0|p|0")))) shouldBe Sent.Ok(1)
        calls.single().run {
            path shouldBe "sendMessage"
            body shouldContain "\"<b>a_b*</b>\""
            body shouldNotContain "parse_mode"
            body shouldContain "f|-100|0|p|0"
        }
    }
    "403 maps to Forbidden" {
        VendeliTg(failingBot(403, "Forbidden: bot was blocked by the user")).send(5, "x") shouldBe Sent.Forbidden
    }
    "other failure maps to Failed" {
        VendeliTg(failingBot(400, "Bad Request: chat not found")).send(5, "x") shouldBe Sent.Failed
    }
    "approve maps errors" {
        listOf(
            "Bad Request: HIDE_REQUESTER_MISSING" to Decision.GONE,
            "Bad Request: USER_ALREADY_PARTICIPANT" to Decision.GONE,
            "Internal Server Error" to Decision.TRANSIENT,
        ).forEach { (d, r) ->
            VendeliTg(failingBot(400, d)).approve(-100, 5) shouldBe r
            VendeliTg(failingBot(400, d)).decline(-100, 5) shouldBe r
        }
    }
    "approve and decline succeed" {
        val calls = mutableListOf<Call>()
        val tg = VendeliTg(recordingBot(calls))
        tg.approve(-100, 5) shouldBe Decision.OK
        tg.decline(-100, 5) shouldBe Decision.OK
        calls.map { it.path } shouldBe listOf("approveChatJoinRequest", "declineChatJoinRequest")
    }
    "edit, answer and document go out" {
        val calls = mutableListOf<Call>()
        val tg = VendeliTg(recordingBot(calls))
        tg.edit(5, 12, "t")
        tg.answer("cb1", "hi", alert = true)
        tg.sendDocument(5, "x.csv", "a,b".toByteArray()) shouldBe Sent.Ok(1)
        calls.map { it.path } shouldBe listOf("editMessageText", "answerCallbackQuery", "sendDocument")
        calls[1].body shouldContain "\"show_alert\":true"
    }
    "failed admins lookup is null" {
        VendeliTg(failingBot(400, "Bad Request")).admins(-100) shouldBe null
    }
    "every T has en and ru" {
        T.entries.forEach { Texts.t("ru", it).shouldNotBeBlank(); Texts.t(null, it).shouldNotBeBlank() }
    }
    "every T formats with its expected args" {
        T.entries.forEach { k ->
            val a = expectedArgs[k] ?: emptyArray()
            Texts.t("ru", k, *a).shouldNotBeBlank()
            Texts.t("en", k, *a).shouldNotContain("%")
        }
    }
    "every Reason has a text" { Reason.entries.forEach { Texts.t(null, it.text()).shouldNotBeBlank() } }
})
