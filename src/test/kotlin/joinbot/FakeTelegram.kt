package joinbot

import eu.vendeli.tgbot.TelegramBot
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SentMsg(val chatId: Long, val text: String, val buttons: Keyboard)
data class EditMsg(val chatId: Long, val messageId: Long, val text: String, val buttons: Keyboard)

/**
 * A fake Telegram Bot API behind a real [bot], for service tests; safe for concurrent coroutines.
 * Each request is parsed back into records. Calls read like
 * "send 5 <text>", "edit 5 12 <text>", "answer cb1 <text> alert", "approve -100 5", "decline -100 5", "doc 5 <name>".
 * Admin lookups are not in [calls]; they go to [adminsCalls] (the chat ids looked up).
 */
class FakeTelegram {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val adminsCalls: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    val sent: MutableList<SentMsg> = Collections.synchronizedList(mutableListOf())
    val edits: MutableList<EditMsg> = Collections.synchronizedList(mutableListOf())

    /** Fixed result for every send/sendDocument; null = Ok with a fresh id. */
    @Volatile var sendResult: Sent? = null
    val sendResultByChat: MutableMap<Long, Sent> = ConcurrentHashMap()
    @Volatile var decideResult: Decision = Decision.OK
    /** A chat without an entry fails the lookup. */
    val adminsOf: MutableMap<Long, List<Admin>> = ConcurrentHashMap()
    /** Status per (chat, user) as getChatMember reports it; absent = "left". */
    val members: MutableMap<Pair<Long, Long>, String> = ConcurrentHashMap()
    @Volatile var memberCount: Int? = null
    /** Result of banChatMember: OK, NO_RIGHT or TRANSIENT (GONE is treated as OK). */
    @Volatile var banResult: Kick = Kick.OK
    /** Declining these users fails at the transport level. */
    val failDeclineFor: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val ids = AtomicLong(100)

    /** The fake's own errors (unknown method, malformed body). Production code swallows them, so FakeTelegramFaults fails the test. */
    val faults: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

    val bot = TelegramBot(token = "000:fake-token-for-tests", httpClient = HttpClient(MockEngine {
        try {
            handle(it.url.encodedPath.substringAfterLast('/'), it.body.toByteArray().decodeToString())
        } catch (e: InjectedFailure) {
            throw e
        } catch (e: Throwable) {
            faults += e; unchecked += e
            throw e
        }
    }))

    private fun result(chatId: Long): Sent = sendResultByChat[chatId] ?: sendResult ?: Sent.Ok(ids.incrementAndGet())

    private fun MockRequestHandleScope.handle(method: String, body: String) = when (method) {
        "sendDocument" -> {
            val chat = Regex("""name=chat_id\r\n[\s\S]*?\r\n\r\n(-?\d+)""").find(body)!!.groupValues[1].toLong()
            val name = Regex("""filename="?([^"\r\n;]+)""").find(body)!!.groupValues[1]
            calls += "doc $chat $name"
            sent(chat, result(chat))
        }
        else -> handleJson(method, if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject)
    }

    private fun MockRequestHandleScope.handleJson(method: String, j: JsonObject) = when (method) {
        "sendMessage" -> {
            val chat = j.long("chat_id"); val text = j.str("text")!!
            calls += "send $chat $text"
            sent += SentMsg(chat, text, j.buttons())
            sent(chat, result(chat))
        }
        "editMessageText" -> {
            val chat = j.long("chat_id"); val id = j.long("message_id"); val text = j.str("text")!!
            calls += "edit $chat $id $text"
            edits += EditMsg(chat, id, text, j.buttons())
            sent(chat, Sent.Ok(id))
        }
        "answerCallbackQuery" -> {
            val alert = j["show_alert"]?.jsonPrimitive?.booleanOrNull == true
            calls += "answer ${j.str("callback_query_id")} ${j.str("text") ?: ""}" + if (alert) " alert" else ""
            ok("true")
        }
        "approveChatJoinRequest", "declineChatJoinRequest" -> {
            val chat = j.long("chat_id"); val user = j.long("user_id")
            val approve = method.startsWith("approve")
            calls += "${if (approve) "approve" else "decline"} $chat $user"
            if (!approve && user in failDeclineFor) throw InjectedFailure()
            when (decideResult) {
                Decision.OK -> ok("true")
                Decision.GONE -> fail(400, "Bad Request: HIDE_REQUESTER_MISSING")
                Decision.TRANSIENT -> fail(500, "Internal Server Error")
            }
        }
        "getChatAdministrators" -> {
            val chat = j.long("chat_id")
            adminsCalls += chat
            adminsOf[chat]?.let { admins -> ok(admins.joinToString(",", "[", "]") { it.json() }) }
                ?: fail(400, "Bad Request: chat not found")
        }
        "getChatMember" -> {
            val chat = j.long("chat_id"); val user = j.long("user_id")
            ok(memberJson(user, members[chat to user] ?: "left"))
        }
        "getChatMemberCount" -> memberCount?.let { ok("$it") } ?: fail(400, "Bad Request: chat not found")
        "banChatMember" -> {
            calls += "ban ${j.long("chat_id")} ${j.long("user_id")}"
            when (banResult) {
                Kick.NO_RIGHT -> fail(400, "Bad Request: not enough rights to restrict/unrestrict chat member")
                Kick.TRANSIENT -> fail(500, "Internal Server Error")
                else -> ok("true")
            }
        }
        "unbanChatMember" -> {
            calls += "unban ${j.long("chat_id")} ${j.long("user_id")}"
            ok("true")
        }
        "getMe" -> ok("""{"id":0,"is_bot":true,"first_name":"Fake","username":"fakebot"}""")
        else -> error("FakeTelegram: unexpected method $method")
    }

    private fun MockRequestHandleScope.sent(chat: Long, r: Sent) = when (r) {
        is Sent.Ok -> ok("""{"message_id":${r.messageId},"date":0,"chat":{"id":$chat,"type":"private"}}""")
        Sent.Forbidden -> fail(403, "Forbidden: bot was blocked by the user")
        Sent.Failed -> fail(500, "Internal Server Error")
    }

    private fun MockRequestHandleScope.ok(result: String) = reply(HttpStatusCode.OK, """{"ok":true,"result":$result}""")

    private fun MockRequestHandleScope.fail(code: Int, description: String) =
        reply(HttpStatusCode.fromValue(code), """{"ok":false,"error_code":$code,"description":"$description"}""")

    private fun MockRequestHandleScope.reply(status: HttpStatusCode, content: String) =
        respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))

    companion object {
        /** Faults of every instance not yet checked; drained by FakeTelegramFaults after each test. */
        private val unchecked: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())

        fun drainFaults(): List<Throwable> = synchronized(unchecked) { unchecked.toList().also { unchecked.clear() } }
    }
}

/** A scripted transport failure, not a fault of the fake. */
private class InjectedFailure : IOException("injected transport failure")

/** Fails any test during which a FakeTelegram hit an error of its own. Registered in io.kotest.provided.ProjectConfig. */
object FakeTelegramFaults : TestCaseExtension {
    override suspend fun intercept(testCase: TestCase, execute: suspend (TestCase) -> TestResult): TestResult {
        FakeTelegram.drainFaults()
        val result = execute(testCase)
        return check(result, FakeTelegram.drainFaults())
    }

    fun check(result: TestResult, faults: List<Throwable>): TestResult =
        if (faults.isEmpty() || result.isErrorOrFailure) result
        else TestResult.Failure(result.duration, AssertionError("FakeTelegram faulted ${faults.size} time(s)", faults.first()))
}

private fun JsonObject.str(key: String) = get(key)?.jsonPrimitive?.content
private fun JsonObject.long(key: String) = str(key)!!.toLong()

private fun JsonObject.buttons(): Keyboard =
    get("reply_markup")?.jsonObject?.get("inline_keyboard")?.jsonArray?.map { row ->
        row.jsonArray.map { Button(it.jsonObject.str("text")!!, it.jsonObject.str("callback_data")!!) }
    } ?: emptyList()

private fun Admin.json() =
    """{"status":"administrator","user":{"id":$userId,"is_bot":$isBot,"first_name":${Json.encodeToString(name)}},
    "can_be_edited":false,"is_anonymous":false,"can_manage_chat":true,"can_delete_messages":false,
    "can_restrict_members":$canBan,"can_promote_members":false,"can_change_info":false,"can_invite_users":$canInvite,
    "can_manage_video_chats":false,"can_post_stories":false,"can_edit_stories":false,"can_delete_stories":false}"""

private fun memberJson(id: Long, status: String): String {
    val user = """"user":{"id":$id,"is_bot":false,"first_name":"User","username":"user"}"""
    val rest = listOf(
        "can_send_messages", "can_send_audios", "can_send_documents", "can_send_photos", "can_send_videos",
        "can_send_video_notes", "can_send_voice_notes", "can_send_polls", "can_send_other_messages",
        "can_add_web_page_previews", "can_react_to_messages", "can_edit_tag", "can_change_info", "can_invite_users",
        "can_pin_messages", "can_manage_topics",
    ).joinToString(",") { """"$it":false""" }
    return when (status) {
        "creator" -> """{"status":"creator",$user,"is_anonymous":false}"""
        "administrator" -> Admin(id, "User", false, false).json().replaceFirst(""""first_name":"User"""", """"first_name":"User","username":"user"""")
        "restricted" -> """{"status":"restricted",$user,"is_member":true,$rest,"until_date":0}"""
        else -> """{"status":"$status",$user${if (status == "kicked") ""","until_date":0""" else ""}}"""
    }
}
