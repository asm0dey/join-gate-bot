package joinbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.internal.KtGramInternal
import eu.vendeli.tgbot.types.common.Update
import eu.vendeli.tgbot.types.component.ChatJoinRequestUpdate
import eu.vendeli.tgbot.types.component.ChatMemberUpdate
import eu.vendeli.tgbot.types.component.MessageReactionUpdate
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.MyChatMemberUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.utils.common.processUpdate
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray

private const val CHAT = -100L
private const val U = 5L
private const val U_CHAT = 55L
private const val ADMIN = 1L

@OptIn(KtGramInternal::class)
private fun upd(json: String): ProcessedUpdate = TG_JSON.decodeFromString(Update.serializer(), json).processUpdate()

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
    val tg = FakeTelegram()
    val groups = GroupRepo(db)
    val forms = FormRepo(db)
    val subs = SubmissionRepo(db, testCrypto())
    val users = BotUserRepo(db)
    val members = MemberRepo(db)
    val admins = AdminCheck(tg.bot, clock, 0)
    val review = ReviewService(subs, forms, groups, users, members, admins, tg.bot, clock)
    val sessions = SessionRepo(db, testCrypto())
    val flow = ApplicantFlow(groups, forms, sessions, subs, users, review, tg.bot, clock)
    val checks = CheckRepo(db)

    init {
        Registry.bot = tg.bot
        Registry.users = users
        Registry.roster = Roster(members)
        Registry.review = review
        Registry.flow = flow
        Registry.checks = CheckService(groups, forms, subs, members, checks, sessions, users, admins, flow, Registry.roster, tg.bot, clock)
        Registry.registry = GroupRegistry(groups, sessions, subs, users, review, flow, Registry.checks, tg.bot)
        groups.upsert(CHAT, "Club", true)
        forms.save(CHAT, Form("Hi", listOf(Text("q1", "Why?"), Text("q2", "Where?"))), 0, 9, clock.instant())
        tg.adminsOf[CHAT] = listOf(Admin(ADMIN, "Boss", false, true))
    }
}

/** Dispatches through vendeli's generated activities with Main's command parsing, as polling does. */
private val router = TelegramBot("000:fake-token-for-tests", "joinbot") { commandParsing { restrictSpacesInCommands = true } }
private suspend fun route(u: ProcessedUpdate) = router.update.handle(u)

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
        ALLOWED_UPDATES.toSet() shouldBe setOf(
            UpdateType.MESSAGE, UpdateType.CALLBACK_QUERY, UpdateType.CHAT_JOIN_REQUEST, UpdateType.MY_CHAT_MEMBER,
            UpdateType.CHAT_MEMBER, UpdateType.MESSAGE_REACTION,
        )
    }

    "group text is ignored" {
        val e = HandlerEnv("h-group")
        fallback(message(CHAT, "hello"))
        e.tg.calls.shouldBeEmpty()
    }

    "chat_member join adds, leave removes" {
        val e = HandlerEnv("h-chat-member")
        fun change(status: String) = upd(
            """{"update_id":4,"chat_member":{"chat":${chat(CHAT)},"from":${user(ADMIN)},"date":1,""" +
                """"old_chat_member":{"status":"left","user":${user(U)}},""" +
                """"new_chat_member":{"status":"$status","user":${user(U)}}}}""",
        ) as ChatMemberUpdate
        memberChanged(change("member"))
        e.members.known(CHAT, U) shouldBe true
        memberChanged(change("left"))
        e.members.known(CHAT, U) shouldBe false
    }

    "group message from a person is seen; from a bot is not" {
        val e = HandlerEnv("h-seen-msg")
        fallback(message(CHAT, "hello"))
        e.members.known(CHAT, U) shouldBe true
        fallback(upd(
            """{"update_id":5,"message":{"message_id":8,"date":1,"chat":${chat(CHAT)},""" +
                """"from":{"id":77,"is_bot":true,"first_name":"Bot"},"text":"hi"}}""",
        ))
        e.members.known(CHAT, 77) shouldBe false
        e.tg.calls.shouldBeEmpty()
    }

    "reaction with a user is seen; anonymous reaction (actor_chat) is not" {
        val e = HandlerEnv("h-reaction")
        reacted(upd(
            """{"update_id":6,"message_reaction":{"chat":${chat(CHAT)},"message_id":7,"user":${user(U)},"date":1,""" +
                """"old_reaction":[],"new_reaction":[{"type":"emoji","emoji":"👍"}]}}""",
        ) as MessageReactionUpdate)
        e.members.known(CHAT, U) shouldBe true
        reacted(upd(
            """{"update_id":7,"message_reaction":{"chat":${chat(CHAT)},"message_id":7,"actor_chat":${chat(CHAT)},"date":1,""" +
                """"old_reaction":[],"new_reaction":[{"type":"emoji","emoji":"👍"}]}}""",
        ) as MessageReactionUpdate)
        e.members.known(CHAT, 0) shouldBe false
        e.members.count(CHAT) shouldBe 1
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

    "a private message with no open form explains how to join" {
        val e = HandlerEnv("h-nosession")
        fallback(message(U, "hello"))
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(U to Texts.t("en", T.HOW_TO_JOIN))
    }

    // R57: vendeli types `from` as non-null, so such an update cannot be decoded; polling must skip it, not wedge.
    "an undecodable update is skipped and the offset moves past it" {
        HandlerEnv("h-batch")
        val good1 = """{"update_id":10,"message":{"message_id":7,"date":1,"chat":${chat(U)},"from":${user(U)},"text":"a"}}"""
        val poison = joinJson("").replace("\"update_id\":3", "\"update_id\":11")
        val good2 = good1.replace("\"update_id\":10", "\"update_id\":12")
        val seen = mutableListOf<Int>()
        val next = processBatch(TG_JSON.parseToJsonElement("[$good1,$poison,$good2]").jsonArray) { seen += it.updateId }
        seen shouldBe listOf(10, 12)
        next shouldBe 13L
    }

    "one sender's updates are handled in arrival order, other senders don't wait" {
        fun msg(id: Int, from: Long, text: String) =
            """{"update_id":$id,"message":{"message_id":$id,"date":1,"chat":${chat(from)},"from":${user(from)},"text":"$text"}}"""
        val batch = listOf(msg(1, U, "m1"), msg(2, U, "m2"), msg(3, 6, "other"), msg(4, U, "m3")).joinToString(",", "[", "]")
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val last = ConcurrentHashMap<Long, Job>()
        withContext(Dispatchers.Default) {
            coroutineScope {
                processBatch(TG_JSON.parseToJsonElement(batch).jsonArray) { u ->
                    launchInOrder(last, orderKey(u)) { if (u.text == "m1") delay(300.milliseconds); seen += u.text }
                }
            }
        }
        seen.filter { it.startsWith("m") } shouldBe listOf("m1", "m2", "m3")
        seen.first() shouldBe "other"
        last.isEmpty() shouldBe true
    }

    "a group upgraded to a supergroup moves to the new id" {
        val e = HandlerEnv("h-migrate")
        e.flow.onJoinRequest(CHAT, U, U_CHAT, Profile("Ann", "ann"), "en")
        val migrate = upd(
            """{"update_id":20,"message":{"message_id":1,"date":1,"chat":{"id":$CHAT,"type":"group","title":"Club"},""" +
                """"from":${user(ADMIN)},"migrate_to_chat_id":-1009}}""",
        )
        chatMigrated(migrate)
        e.groups.get(CHAT) shouldBe null
        e.groups.get(-1009)!!.active shouldBe true
        e.forms.current(-1009)!!.first shouldBe 1
        e.sessions.get(U, -1009)!!.step shouldBe 0
        chatMigrated(migrate) // a repeat is a no-op
        e.groups.get(-1009)!!.title shouldBe "Club"
        e.tg.calls.filter { it.startsWith("send $CHAT") || it.startsWith("send -1009") }.shouldBeEmpty()
    }

    "/start with a check payload reaches enter" {
        val e = HandlerEnv("h-start-check")
        e.checks.open(CHAT, e.clock.instant().plusSeconds(3600), ADMIN, e.clock.instant())
        e.tg.members[CHAT to U] = "member"
        route(message(U, "/start r-100"))
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(U to Texts.t("en", T.FORM_FOR, "Club"), U to "Hi", U to "Why?")
        e.sessions.get(U, CHAT)!!.kind shouldBe Kind.CHECK
    }

    "/remind@joinbot 3d in the group starts a check" {
        val e = HandlerEnv("h-remind")
        e.tg.adminsOf[CHAT] = listOf(Admin(U, "Ann", false, true, true), Admin(0, "Bot", true, true, true))
        route(message(CHAT, "/remind@joinbot 3d"))
        e.checks.openCheck(CHAT)!!.deadline shouldBe e.clock.instant().plus(java.time.Duration.ofDays(3))
        e.tg.sent.first().buttons.flatten().map { it.data } shouldBe listOf("https://t.me/joinbot?start=r-100")
    }

    "/remind from an anonymous admin gets an explanation" {
        val e = HandlerEnv("h-remind-anon")
        route(upd(
            """{"update_id":8,"message":{"message_id":9,"date":1,"chat":${chat(CHAT)},"sender_chat":${chat(CHAT)},""" +
                """"from":{"id":1087968824,"is_bot":true,"first_name":"Group","username":"GroupAnonymousBot"},"text":"/remind"}}""",
        ))
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(CHAT to Texts.t(null, T.REMIND_ANONYMOUS))
        e.checks.get(CHAT) shouldBe null
    }

    "plain /start offers updates for passed groups" {
        val e = HandlerEnv("h-start-update")
        e.members.pass(CHAT, U, e.clock.instant())
        route(message(U, "/start"))
        e.tg.sent.single() shouldBe SentMsg(U, Texts.t("en", T.UPDATE_LIST), listOf(listOf(Button("Club", "u|$CHAT"))))
        e.tg.members[CHAT to U] = "member"
        fallback(callback(U, "u|$CHAT"))
        e.tg.calls.filter { it.startsWith("answer") } shouldBe listOf("answer cb ")
        e.sessions.get(U, CHAT)!!.kind shouldBe Kind.UPDATE
    }

    "/remind addressed to another bot is ignored; our name matches in any case" {
        val e = HandlerEnv("h-remind-other")
        e.tg.adminsOf[CHAT] = listOf(Admin(U, "Ann", false, true, true), Admin(0, "Bot", true, true, true))
        route(message(CHAT, "/remind@otherbot 3d"))
        e.tg.calls.shouldBeEmpty()
        e.checks.get(CHAT) shouldBe null
        route(message(CHAT, "/remind@JoinBot 3d"))
        e.checks.openCheck(CHAT)!!.deadline shouldBe e.clock.instant().plus(java.time.Duration.ofDays(3))
    }
    "plain /start delivers an undelivered deadline list; a Remove click reaches the check" {
        val e = HandlerEnv("h-deadline")
        e.tg.adminsOf[CHAT] = listOf(Admin(U, "Ann", false, true, true))
        e.checks.open(CHAT, e.clock.instant(), U, e.clock.instant())
        e.checks.close(CHAT, e.clock.instant())
        e.checks.notice(CHAT, U, false)
        route(message(U, "/start"))
        e.tg.sent.map { it.chatId to it.text } shouldBe listOf(U to Texts.t("en", T.ALL_PASSED, "Club"))

        e.members.seen(CHAT, 7); e.tg.members[CHAT to 7L] = "member"
        val rows = listOf(7, 8).joinToString(",") { """[{"text":"Remove $it","callback_data":"k|$CHAT|$it"}]""" }
        fallback(upd(
            """{"update_id":9,"callback_query":{"id":"cb","from":${user(U)},"chat_instance":"i","data":"k|$CHAT|7",""" +
                """"message":{"message_id":900,"date":1,"chat":${chat(U)},"text":"list","reply_markup":{"inline_keyboard":[$rows]}}}}""",
        ))
        e.tg.calls.filter { it.startsWith("ban") } shouldBe listOf("ban $CHAT 7")
        e.tg.edits.single() shouldBe EditMsg(U, 900, "list", listOf(listOf(Button("Remove 8", "k|$CHAT|8"))))
    }
})
