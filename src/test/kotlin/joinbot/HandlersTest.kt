package joinbot

import eu.vendeli.tgbot.annotations.internal.KtGramInternal
import eu.vendeli.tgbot.types.common.Update
import eu.vendeli.tgbot.types.component.ChatJoinRequestUpdate
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.MyChatMemberUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.utils.common.processUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

private const val CHAT = -100L
private const val U = 5L
private const val U_CHAT = 55L
private const val ADMIN = 1L

/** Mirrors vendeli's internal `serde`, which getUpdates decodes with. */
@OptIn(ExperimentalSerializationApi::class)
private val tgJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

@OptIn(KtGramInternal::class)
private fun upd(json: String): ProcessedUpdate = tgJson.decodeFromString(Update.serializer(), json).processUpdate()

private fun user(id: Long) = """{"id":$id,"is_bot":false,"first_name":"Ann","username":"ann","language_code":"en"}"""
private fun chat(id: Long) = """{"id":$id,"type":"${if (id < 0) "supergroup" else "private"}","title":"Club"}"""
private fun message(chatId: Long, text: String) =
    upd("""{"update_id":1,"message":{"message_id":7,"date":1,"chat":${chat(chatId)},"from":${user(U)},"text":"$text"}}""")
private fun callback(from: Long, data: String) = upd(
    """{"update_id":2,"callback_query":{"id":"cb","from":${user(from)},"chat_instance":"i","data":"$data",""" +
        """"message":{"message_id":900,"date":1,"chat":${chat(from)}}}}""",
)
private fun joinJson(from: String) =
    """{"update_id":3,"chat_join_request":{"chat":${chat(CHAT)},$from"user_chat_id":$U_CHAT,"date":1}}"""

private class HandlerEnv(name: String) {
    val db = testDb(name)
    val clock = TestClock()
    val tg = FakeTg()
    val groups = GroupRepo(db)
    val forms = FormRepo(db)
    val subs = SubmissionRepo(db, testCrypto())
    val users = BotUserRepo(db)
    val review = ReviewService(subs, forms, groups, users, AdminCheck(tg, clock), tg, clock)
    val flow = ApplicantFlow(groups, forms, SessionRepo(db, testCrypto()), subs, users, review, tg, clock)

    init {
        Registry.tg = tg
        Registry.users = users
        Registry.review = review
        Registry.flow = flow
        Registry.registry = GroupRegistry(groups, subs, users, review, flow, tg)
        groups.upsert(CHAT, "Club", true)
        forms.save(CHAT, Form("Hi", listOf(Text("q1", "Why?"), Text("q2", "Where?"))), 0, 9, clock.instant())
        tg.adminsOf[CHAT] = listOf(Admin(ADMIN, "Boss", false, true))
    }
}

class HandlersTest : StringSpec({
    "join request update reaches the flow" {
        val e = HandlerEnv("h-join")
        joinRequest(upd(joinJson(""""from":${user(U)},""")) as ChatJoinRequestUpdate)
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(U_CHAT to "Hi", U_CHAT to "Why?")
    }

    "private text and f| callback route to the flow, r| to review" {
        val e = HandlerEnv("h-route")
        e.flow.onJoinRequest(CHAT, U, U_CHAT, Profile("Ann", "ann"), "en")
        fallback(message(U, "because"))
        e.tg.sent.last().let { it.chatId to it.text } shouldBe (U to "Where?")

        fallback(callback(U, cbData(CHAT, 0, 'S')))
        e.tg.calls.last() shouldBe "answer cb ${Texts.t("en", T.STALE_BUTTON)} alert"

        e.groups.upsert(-200, "Gone", false)
        val sid = e.subs.create(-200, U, null, Profile("Ann", "ann"), null, Status.PENDING, e.clock.instant())
        fallback(callback(ADMIN, "r|$sid|a"))
        e.tg.calls.last() shouldBe "answer cb ${Texts.t(null, T.REVIEW_SUSPENDED)} alert"

        fallback(callback(ADMIN, "x|1"))
        e.tg.calls.last() shouldBe "answer cb "
    }

    "polling asks Telegram for every update kind a handler needs" {
        ALLOWED_UPDATES.toSet() shouldBe setOf(UpdateType.MESSAGE, UpdateType.CALLBACK_QUERY, UpdateType.CHAT_JOIN_REQUEST, UpdateType.MY_CHAT_MEMBER)
    }

    "group text is ignored" {
        val e = HandlerEnv("h-group")
        fallback(message(CHAT, "hello"))
        e.tg.calls.shouldBeEmpty()
    }

    "/start from a stranger explains how to join" {
        val e = HandlerEnv("h-start")
        (message(U, "/start") as MessageUpdate).let { start(it.user, it) }
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(U to Texts.t("en", T.HOW_TO_JOIN))
        e.users.dmOk(U) shouldBe true
    }

    "/start in a group is ignored" {
        val e = HandlerEnv("h-start-group")
        (message(CHAT, "/start") as MessageUpdate).let { start(it.user, it) }
        e.tg.calls.shouldBeEmpty()
        e.users.dmOk(U) shouldBe false
    }

    "promotion with the invite right activates the group" {
        val e = HandlerEnv("h-status")
        e.groups.upsert(-300, "New", false)
        botStatus(upd(
            """{"update_id":4,"my_chat_member":{"chat":{"id":-300,"type":"supergroup","title":"New"},"from":${user(ADMIN)},"date":1,""" +
                """"old_chat_member":{"status":"member","user":${user(9)}},""" +
                """"new_chat_member":{"status":"administrator","user":${user(9)},"can_be_edited":false,"is_anonymous":false,""" +
                """"can_manage_chat":true,"can_delete_messages":false,"can_manage_video_chats":false,"can_restrict_members":false,""" +
                """"can_promote_members":false,"can_change_info":false,"can_invite_users":true,"can_post_stories":false,""" +
                """"can_edit_stories":false,"can_delete_stories":false}}}""",
        ) as MyChatMemberUpdate)
        e.groups.get(-300)!!.active shouldBe true
    }

    // R57: vendeli types `from` as non-null, so such an update fails decoding and no handler ever sees it.
    // Telegram documents `from` as required here; Main's bounded restart loop is the backstop if it ever is not.
    "a join request without from never reaches a handler" {
        HandlerEnv("h-nofrom")
        shouldThrow<SerializationException> { upd(joinJson("")) }
    }
})
