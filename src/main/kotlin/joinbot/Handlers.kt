package joinbot

import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.annotations.UnprocessedHandler
import eu.vendeli.tgbot.annotations.UpdateHandler
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.ChatMember
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.component.CallbackQueryUpdate
import eu.vendeli.tgbot.types.component.ChatJoinRequestUpdate
import eu.vendeli.tgbot.types.component.ChatMemberUpdate
import eu.vendeli.tgbot.types.component.MessageKind
import eu.vendeli.tgbot.types.component.MessageReactionUpdate
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.MyChatMemberUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.types.msg.Message
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("joinbot.Handlers")

/** vendeli logs a handler's exception together with the whole update, and tinylog silences that logger; this keeps the class name. */
private inline fun guarded(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("{} failed: {}", what, e.javaClass.simpleName)
    }
}

private fun ProcessedUpdate.isPrivate() = (this as? MessageUpdate)?.message?.chat?.type == ChatType.Private

private fun User.profile() = Profile(listOfNotNull(firstName, lastName).joinToString(" "), username)

private val START_CHECK = Regex("""/start r(-?\d+)""")

// One place parses `/remind`: no @CommandHandler, so `/remind@botname` and the argument need no second parser.
private val REMIND = Regex("""/remind(@\w+)?(?:\s+(\S+))?\s*""")

/**
 * `/start r<chatId>` enters that group's check. Plain `/start` resumes an open form, else hands an admin their
 * pending reviews and undelivered deadline lists, else offers updates to a passed member, else explains how to join.
 */
@CommandHandler(["/start"])
suspend fun start(user: User, update: ProcessedUpdate): Unit = guarded("start") {
    if (!update.isPrivate()) return
    val lang = user.languageCode
    Registry.users.started(user.id, lang)
    val chatId = START_CHECK.matchEntire((update as MessageUpdate).message.text.orEmpty())?.groupValues?.get(1)?.toLongOrNull()
    if (chatId != null) return Registry.checks.enter(chatId, user.profile(), user.id, lang)
    if (Registry.flow.onStart(user.id, lang)) return
    val reviews = Registry.review.deliverPending(user.id)
    if (reviews + Registry.checks.deliverNotices(user.id) > 0) return
    if (Registry.checks.offerUpdates(user.id, lang)) return
    Registry.bot.sendText(user.id, Texts.t(lang, T.HOW_TO_JOIN))
}

@UpdateHandler([UpdateType.CHAT_JOIN_REQUEST])
suspend fun joinRequest(update: ChatJoinRequestUpdate): Unit = guarded("join request") {
    val r = update.chatJoinRequest
    val u = r.from
    Registry.flow.onJoinRequest(r.chat.id, u.id, r.userChatId, u.profile(), u.languageCode)
}

@UpdateHandler([UpdateType.MY_CHAT_MEMBER])
suspend fun botStatus(update: MyChatMemberUpdate): Unit = guarded("bot status") {
    val m = update.myChatMember
    if (m.chat.type != ChatType.Group && m.chat.type != ChatType.Supergroup) return
    val admin = m.newChatMember as? ChatMember.Administrator
    Registry.registry.onBotStatus(m.chat.id, m.chat.title.orEmpty(), admin != null, admin?.canInviteUsers == true)
}

/** Join and leave keep the roster current; bots are never members to check. */
@UpdateHandler([UpdateType.CHAT_MEMBER])
suspend fun memberChanged(update: ChatMemberUpdate): Unit = guarded("member change") {
    val m = update.chatMember
    val u = m.newChatMember.user
    if (u.isBot) return
    when (val n = m.newChatMember) {
        is ChatMember.Left, is ChatMember.Banned -> Registry.roster.left(m.chat.id, u.id)
        is ChatMember.Restricted -> if (n.isMember) Registry.roster.seen(m.chat.id, u.id) else Registry.roster.left(m.chat.id, u.id)
        else -> Registry.roster.seen(m.chat.id, u.id)
    }
}

/** A reaction shows a person is there; anonymous ones (actor_chat) carry no user. */
@UpdateHandler([UpdateType.MESSAGE_REACTION])
suspend fun reacted(update: MessageReactionUpdate): Unit = guarded("reaction") {
    val r = update.messageReaction
    val u = r.user ?: return
    if (!u.isBot) Registry.roster.seen(r.chat.id, u.id)
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

/** Everything no command matched: form answers in private, and every button. In groups, `/remind`; other chatter only feeds the roster. */
@UnprocessedHandler
suspend fun fallback(update: ProcessedUpdate): Unit = guarded("update") {
    when (update) {
        is MessageUpdate -> {
            val m = update.message
            if (m.chat.type == ChatType.Group || m.chat.type == ChatType.Supergroup) {
                val from = m.from
                if (from != null && !from.isBot && m.senderChat == null) Registry.roster.seen(m.chat.id, from.id)
                val remind = REMIND.matchEntire(m.text ?: return) ?: return
                // an anonymous admin posts as the group: from is GroupAnonymousBot, sender_chat the group
                val anonymous = m.senderChat?.id == m.chat.id
                Registry.checks.remind(
                    m.chat.id, (from ?: return).id, anonymous, remind.groupValues[2].ifEmpty { null },
                    remind.groupValues[1].removePrefix("@").ifEmpty { null },
                )
                return
            }
            if (!update.isPrivate()) return
            val u = update.user
            if (!Registry.flow.onMessage(u.id, update.message.text, u.languageCode)) {
                Registry.bot.sendText(u.id, Texts.t(u.languageCode, T.HOW_TO_JOIN))
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
                data.startsWith("u|") ->
                    Registry.checks.onUpdateButton(q.from.id, q.id, data, q.from.profile(), q.from.languageCode)
                data.startsWith("k|") && q.message is Message -> {
                    val m = q.message as Message
                    val keyboard = m.replyMarkup?.keyboard.orEmpty().map { row -> row.map { Button(it.text, it.callbackData.orEmpty()) } }
                    Registry.checks.onRemoveButton(q.from.id, q.id, data, m.messageId, m.text.orEmpty(), keyboard)
                }
                else -> Registry.bot.answerCallback(q.id)
            }
        }

        else -> {} // join requests and bot-status updates also land here after their @UpdateHandler
    }
}
