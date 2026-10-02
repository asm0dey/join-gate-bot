package joinbot

data class Button(val text: String, val data: String)

sealed interface Sent {
    data class Ok(val messageId: Long) : Sent
    data object Forbidden : Sent
    data object Failed : Sent
}

/** GONE = HIDE_REQUESTER_MISSING or USER_ALREADY_PARTICIPANT in the error description. */
enum class Decision { OK, GONE, TRANSIENT }

/** [name] is the user's first name, for "decided by Y". The chat owner always has [canInvite]. */
data class Admin(val userId: Long, val name: String, val isBot: Boolean, val canInvite: Boolean)

/** The only door to Telegram for every service; tests use FakeTg. */
interface Tg {
    suspend fun send(chatId: Long, text: String, buttons: List<List<Button>> = emptyList()): Sent
    suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<Button>> = emptyList())
    suspend fun answer(callbackId: String, text: String? = null, alert: Boolean = false)
    suspend fun approve(chatId: Long, userId: Long): Decision
    suspend fun decline(chatId: Long, userId: Long): Decision

    /** Null on failure. */
    suspend fun admins(chatId: Long): List<Admin>?
    suspend fun sendDocument(chatId: Long, fileName: String, bytes: ByteArray): Sent
}
