package joinbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.answer.answerCallbackQuery
import eu.vendeli.tgbot.api.chat.approveChatJoinRequest
import eu.vendeli.tgbot.api.chat.declineChatJoinRequest
import eu.vendeli.tgbot.api.chat.getChatAdministrators
import eu.vendeli.tgbot.api.media.document
import eu.vendeli.tgbot.api.message.editMessageText
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.chat.ChatMember
import eu.vendeli.tgbot.types.component.InputFile
import eu.vendeli.tgbot.types.component.Response
import eu.vendeli.tgbot.utils.builders.InlineKeyboardMarkupBuilder
import kotlinx.coroutines.CancellationException

/** Plain text only: parse_mode is never set, so admin- and user-written text needs no escaping. */
class VendeliTg(private val bot: TelegramBot) : Tg {
    private fun InlineKeyboardMarkupBuilder.rows(buttons: List<List<Button>>) =
        buttons.forEach { row -> row.forEach { it.text callback it.data }; br() }

    /** A thrown transport error becomes a Failure-shaped null; cancellation still propagates. */
    private suspend fun <T> call(block: suspend () -> Response<T>): Response<T>? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun Response<*>?.toDecision(): Decision = when {
        this is Response.Success -> Decision.OK
        this is Response.Failure && (description.orEmpty().contains("HIDE_REQUESTER_MISSING") ||
            description.orEmpty().contains("USER_ALREADY_PARTICIPANT")) -> Decision.GONE
        else -> Decision.TRANSIENT
    }

    private fun Response<*>?.toSent(): Sent = when {
        this is Response.Success -> Sent.Ok((result as eu.vendeli.tgbot.types.msg.Message).messageId)
        this is Response.Failure && errorCode == 403 -> Sent.Forbidden
        else -> Sent.Failed
    }

    override suspend fun send(chatId: Long, text: String, buttons: List<List<Button>>): Sent {
        val action = message { text }.let { if (buttons.isEmpty()) it else it.inlineKeyboardMarkup { rows(buttons) } }
        return call { action.sendReturning(chatId, bot).await() }.toSent()
    }

    override suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<Button>>) {
        val action = editMessageText(messageId) { text }
            .let { if (buttons.isEmpty()) it else it.inlineKeyboardMarkup { rows(buttons) } }
        call { action.sendReturning(chatId, bot).await() }
    }

    override suspend fun answer(callbackId: String, text: String?, alert: Boolean) {
        val action = answerCallbackQuery(callbackId).options { this.text = text; showAlert = alert }
        call { action.sendReturning(0L, bot).await() }
    }

    override suspend fun approve(chatId: Long, userId: Long): Decision =
        call { approveChatJoinRequest(userId).sendReturning(chatId, bot).await() }.toDecision()

    override suspend fun decline(chatId: Long, userId: Long): Decision =
        call { declineChatJoinRequest(userId).sendReturning(chatId, bot).await() }.toDecision()

    override suspend fun admins(chatId: Long): List<Admin>? {
        val members = (call { getChatAdministrators().sendReturning(chatId, bot).await() } as? Response.Success)
            ?.result ?: return null
        return members.mapNotNull {
            when (it) {
                is ChatMember.Owner -> Admin(it.user.id, it.user.firstName, it.user.isBot, true)
                is ChatMember.Administrator -> Admin(it.user.id, it.user.firstName, it.user.isBot, it.canInviteUsers)
                else -> null
            }
        }
    }

    override suspend fun sendDocument(chatId: Long, fileName: String, bytes: ByteArray): Sent {
        val action = document(InputFile(bytes, fileName, "application/octet-stream"))
        return call { action.sendReturning(chatId, bot).await() }.toSent()
    }
}
