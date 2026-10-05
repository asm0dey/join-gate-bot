package joinbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.internal.KtGramInternal
import eu.vendeli.tgbot.api.answer.answerCallbackQuery
import eu.vendeli.tgbot.api.botactions.getMe
import eu.vendeli.tgbot.api.chat.approveChatJoinRequest
import eu.vendeli.tgbot.api.chat.banChatMember
import eu.vendeli.tgbot.api.chat.declineChatJoinRequest
import eu.vendeli.tgbot.api.chat.getChatAdministrators
import eu.vendeli.tgbot.api.chat.getChatMember
import eu.vendeli.tgbot.api.chat.getChatMemberCount
import eu.vendeli.tgbot.api.chat.unbanChatMember
import eu.vendeli.tgbot.api.media.document
import eu.vendeli.tgbot.api.message.editMessageText
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.chat.ChatMember
import eu.vendeli.tgbot.types.component.InputFile
import eu.vendeli.tgbot.types.component.Response
import eu.vendeli.tgbot.types.msg.Message
import eu.vendeli.tgbot.utils.builders.InlineKeyboardMarkupBuilder
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("joinbot.Telegram")

// Every Telegram call the services make. Plain text only: parse_mode is never set, so admin- and user-written
// text needs no escaping. Only a refused send logs, and only Telegram's reason.

/** A callback button, or a link button when [url] is set ([data] is then unused). */
data class Button(val text: String, val data: String = "", val url: String? = null)

typealias Keyboard = List<List<Button>>

sealed interface Sent {
    data class Ok(val messageId: Long) : Sent
    data object Forbidden : Sent
    data object Failed : Sent
}

/** GONE = HIDE_REQUESTER_MISSING or USER_ALREADY_PARTICIPANT in the error description. */
enum class Decision { OK, GONE, TRANSIENT }

/** [name] is the user's first name, for "decided by Y". The chat owner always has [canInvite] and [canBan]. */
data class Admin(val userId: Long, val name: String, val isBot: Boolean, val canInvite: Boolean, val canBan: Boolean = false)

/** GONE = not in the chat any more; NO_RIGHT = the bot lacks Ban users. */
enum class Kick { OK, GONE, NO_RIGHT, TRANSIENT }

enum class Membership { MEMBER, ADMIN, GONE }

data class MemberInfo(val membership: Membership, val profile: Profile)

suspend fun TelegramBot.sendText(chatId: Long, text: String, buttons: Keyboard = emptyList()): Sent {
    val action = message { text }.let { if (buttons.isEmpty()) it else it.inlineKeyboardMarkup { rows(buttons) } }
    return call { action.sendReturning(chatId, this).await() }.toSent()
}

suspend fun TelegramBot.editText(chatId: Long, messageId: Long, text: String, buttons: Keyboard = emptyList()) {
    val action = editMessageText(messageId) { text }
        .let { if (buttons.isEmpty()) it else it.inlineKeyboardMarkup { rows(buttons) } }
    call { action.sendReturning(chatId, this).await() }
}

@OptIn(KtGramInternal::class)
suspend fun TelegramBot.answerCallback(callbackId: String, text: String? = null, alert: Boolean = false) {
    val action = answerCallbackQuery(callbackId).options { this.text = text; showAlert = alert }
    // answerCallbackQuery takes no chat, but vendeli 9.6 types it as a chat Action whose public send adds chat_id.
    // doRequestReturning is the only chat-less path; TelegramTest pins the body if a vendeli bump changes it.
    call { action.run { doRequestReturning(this@answerCallback) }.await() }
}

suspend fun TelegramBot.approveJoin(chatId: Long, userId: Long): Decision =
    call { approveChatJoinRequest(userId).sendReturning(chatId, this).await() }.toDecision()

suspend fun TelegramBot.declineJoin(chatId: Long, userId: Long): Decision =
    call { declineChatJoinRequest(userId).sendReturning(chatId, this).await() }.toDecision()

/** Null on failure. */
suspend fun TelegramBot.chatAdmins(chatId: Long): List<Admin>? {
    val members = (call { getChatAdministrators().sendReturning(chatId, this).await() } as? Response.Success)
        ?.result ?: return null
    return members.mapNotNull {
        when (it) {
            is ChatMember.Owner -> Admin(it.user.id, it.user.firstName, it.user.isBot, true, true)
            is ChatMember.Administrator ->
                Admin(it.user.id, it.user.firstName, it.user.isBot, it.canInviteUsers, it.canRestrictMembers)
            else -> null
        }
    }
}

/** Null on failure. A restricted user who already left (is_member false) counts as GONE. */
suspend fun TelegramBot.memberInfo(chatId: Long, userId: Long): MemberInfo? {
    val m = (call { getChatMember(userId).sendReturning(chatId, this).await() } as? Response.Success)
        ?.result ?: return null
    val u = m.user
    val membership = when {
        m is ChatMember.Restricted && !m.isMember -> Membership.GONE
        m is ChatMember.Member || m is ChatMember.Restricted -> Membership.MEMBER
        m is ChatMember.Administrator || m is ChatMember.Owner -> Membership.ADMIN
        else -> Membership.GONE
    }
    return MemberInfo(membership, Profile(listOfNotNull(u.firstName, u.lastName).joinToString(" "), u.username))
}

/** Null on failure. */
suspend fun TelegramBot.memberCount(chatId: Long): Int? =
    (call { getChatMemberCount().sendReturning(chatId, this).await() } as? Response.Success)?.result

/** Ban then unban, so the person can rejoin. Unban failure is logged only: they are out either way. */
suspend fun TelegramBot.removeMember(chatId: Long, userId: Long): Kick {
    val info = memberInfo(chatId, userId) ?: return Kick.TRANSIENT
    if (info.membership == Membership.GONE) return Kick.GONE
    val ban = call { banChatMember(userId).sendReturning(chatId, this).await() } ?: return Kick.TRANSIENT
    // ponytail: an admin promoted mid-check also lands in TRANSIENT (its ban fails with another reason)
    if (ban is Response.Failure) return if (ban.description.orEmpty().contains("not enough rights")) Kick.NO_RIGHT else Kick.TRANSIENT
    if (call { unbanChatMember(userId, onlyIfBanned = true).sendReturning(chatId, this).await() } !is Response.Success) {
        log.warn("unban after removal failed")
    }
    return Kick.OK
}

/** Null on failure. One getMe. */
suspend fun TelegramBot.botUsername(): String? =
    (call { getMe().sendReturning(this).await() } as? Response.Success)?.result?.username

suspend fun TelegramBot.sendFile(chatId: Long, fileName: String, bytes: ByteArray): Sent {
    val action = document(InputFile(bytes, fileName, "application/octet-stream"))
    return call { action.sendReturning(chatId, this).await() }.toSent()
}

private fun InlineKeyboardMarkupBuilder.rows(buttons: Keyboard) =
    buttons.forEach { row -> row.forEach { if (it.url != null) it.text url it.url else it.text callback it.data }; br() }

/** A thrown transport error becomes a Failure-shaped null; cancellation still propagates. */
private suspend fun <T> call(block: suspend () -> Response<T>): Response<T>? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private fun Response<*>?.toDecision(): Decision = when (this) {
    is Response.Success -> Decision.OK
    is Response.Failure if (description.orEmpty().contains("HIDE_REQUESTER_MISSING") ||
            description.orEmpty().contains("USER_ALREADY_PARTICIPANT")) -> Decision.GONE

    else -> Decision.TRANSIENT
}

private fun Response<*>?.toSent(): Sent = when (this) {
    is Response.Success -> Sent.Ok((result as? Message)?.messageId ?: return Sent.Failed)
    is Response.Failure if errorCode == 403 -> {
        // Telegram's fixed reason ("bot was blocked by the user", "bot can't initiate conversation…"); no user data
        log.warn("send refused: {}", description)
        Sent.Forbidden
    }
    else -> Sent.Failed
}
