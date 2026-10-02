package joinbot

import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.annotations.UnprocessedHandler
import eu.vendeli.tgbot.annotations.UpdateHandler
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.ChatMember
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.component.CallbackQueryUpdate
import eu.vendeli.tgbot.types.component.ChatJoinRequestUpdate
import eu.vendeli.tgbot.types.component.MessageKind
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.MyChatMemberUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("joinbot.Handlers")

/** vendeli logs a handler's exception together with the whole update, and tinylog silences that logger; this keeps the class name. */
private suspend inline fun guarded(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("{} failed: {}", what, e.javaClass.simpleName)
    }
}

private fun ProcessedUpdate.isPrivate() = (this as? MessageUpdate)?.message?.chat?.type == ChatType.Private

/** Resumes an open form, else hands an admin their pending reviews, else explains how to join. */
@CommandHandler(["/start"])
suspend fun start(user: User, update: ProcessedUpdate): Unit = guarded("start") {
    if (!update.isPrivate()) return
    val lang = user.languageCode
    Registry.users.started(user.id, lang)
    if (Registry.flow.onStart(user.id, lang)) return
    if (Registry.review.deliverPending(user.id) > 0) return
    Registry.tg.send(user.id, Texts.t(lang, T.HOW_TO_JOIN))
}

@UpdateHandler([UpdateType.CHAT_JOIN_REQUEST])
suspend fun joinRequest(update: ChatJoinRequestUpdate): Unit = guarded("join request") {
    val r = update.chatJoinRequest
    val u = r.from
    val profile = Profile(listOfNotNull(u.firstName, u.lastName).joinToString(" "), u.username)
    Registry.flow.onJoinRequest(r.chat.id, u.id, r.userChatId, profile, u.languageCode)
}

@UpdateHandler([UpdateType.MY_CHAT_MEMBER])
suspend fun botStatus(update: MyChatMemberUpdate): Unit = guarded("bot status") {
    val m = update.myChatMember
    if (m.chat.type != ChatType.Group && m.chat.type != ChatType.Supergroup) return
    val admin = m.newChatMember as? ChatMember.Administrator
    Registry.registry.onBotStatus(m.chat.id, m.chat.title.orEmpty(), admin != null, admin?.canInviteUsers == true)
}

/**
 * Telegram announces a group's upgrade to a supergroup as an ordinary message in the old chat carrying
 * `migrate_to_chat_id`; there is no dedicated update type. Nothing is said back.
 */
@UpdateHandler([UpdateType.MESSAGE], messageKind = [MessageKind.MIGRATE_TO_CHAT])
suspend fun chatMigrated(update: ProcessedUpdate): Unit = guarded("migration") {
    val m = (update as? MessageUpdate)?.message ?: return
    Registry.registry.onMigrated(m.chat.id, m.migrateToChatId ?: return)
}

/** Everything no command matched: form answers in private, and every button. Group chatter is ignored. */
@UnprocessedHandler
suspend fun fallback(update: ProcessedUpdate): Unit = guarded("update") {
    when (update) {
        is MessageUpdate -> {
            if (!update.isPrivate()) return
            val u = update.user
            if (!Registry.flow.onMessage(u.id, update.message.text, u.languageCode)) {
                Registry.tg.send(u.id, Texts.t(u.languageCode, T.HOW_TO_JOIN))
            }
        }
        is CallbackQueryUpdate -> {
            val q = update.callbackQuery
            val data = q.data.orEmpty()
            val messageId = q.message?.messageId
            when {
                data.startsWith("f|") && messageId != null ->
                    Registry.flow.onCallback(q.from.id, q.id, data, q.from.languageCode, messageId)
                data.startsWith("r|") -> Registry.review.onDecision(q.from.id, q.id, data)
                else -> Registry.tg.answer(q.id)
            }
        }
        else -> {} // join requests and bot-status updates also land here after their @UpdateHandler
    }
}
