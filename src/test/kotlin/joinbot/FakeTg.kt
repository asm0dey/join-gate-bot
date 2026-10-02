package joinbot

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SentMsg(val chatId: Long, val text: String, val buttons: List<List<Button>>)
data class EditMsg(val chatId: Long, val messageId: Long, val text: String, val buttons: List<List<Button>>)

/**
 * Recording Tg for service tests; safe for concurrent coroutines. Calls read like
 * "send 5 <text>", "edit 5 12 <text>", "answer cb1 <text> alert", "approve -100 5", "decline -100 5", "doc 5 <name>".
 */
class FakeTg : Tg {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val sent: MutableList<SentMsg> = Collections.synchronizedList(mutableListOf())
    val edits: MutableList<EditMsg> = Collections.synchronizedList(mutableListOf())

    /** Fixed result for every send/sendDocument; null = Ok with a fresh id. */
    @Volatile var sendResult: Sent? = null
    val sendResultByChat: MutableMap<Long, Sent> = ConcurrentHashMap()
    @Volatile var decideResult: Decision = Decision.OK
    val adminsOf: MutableMap<Long, List<Admin>> = ConcurrentHashMap()
    val throwOnDeclineFor: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val ids = AtomicLong(100)

    private fun result(chatId: Long): Sent = sendResultByChat[chatId] ?: sendResult ?: Sent.Ok(ids.incrementAndGet())

    override suspend fun send(chatId: Long, text: String, buttons: List<List<Button>>): Sent {
        calls += "send $chatId $text"
        sent += SentMsg(chatId, text, buttons)
        return result(chatId)
    }

    override suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<Button>>) {
        calls += "edit $chatId $messageId $text"
        edits += EditMsg(chatId, messageId, text, buttons)
    }

    override suspend fun answer(callbackId: String, text: String?, alert: Boolean) {
        calls += "answer $callbackId ${text ?: ""}" + if (alert) " alert" else ""
    }

    override suspend fun approve(chatId: Long, userId: Long): Decision {
        calls += "approve $chatId $userId"
        return decideResult
    }

    override suspend fun decline(chatId: Long, userId: Long): Decision {
        calls += "decline $chatId $userId"
        if (userId in throwOnDeclineFor) error("decline failed")
        return decideResult
    }

    override suspend fun admins(chatId: Long): List<Admin>? = adminsOf[chatId]

    override suspend fun sendDocument(chatId: Long, fileName: String, bytes: ByteArray): Sent {
        calls += "doc $chatId $fileName"
        return result(chatId)
    }
}
