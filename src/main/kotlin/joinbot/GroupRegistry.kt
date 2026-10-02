package joinbot

import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

/** Reacts to the bot being added, promoted, demoted or removed in a group. */
class GroupRegistry(private val groups: GroupRepo, private val sessions: SessionRepo, private val users: BotUserRepo, private val tg: Tg) {
    suspend fun onBotStatus(chatId: Long, title: String, isAdmin: Boolean, canInvite: Boolean) {
        val active = isAdmin && canInvite
        groups.upsert(chatId, title, active)
        if (active) return
        // every my_chat_member update is a real admin action, so the reminder repeats
        if (isAdmin) tg.send(chatId, Texts.t(null, T.NEEDS_INVITE_RIGHT))
        sessions.deleteForChat(chatId).forEach {
            try {
                tg.send(it.userId, Texts.t(it.lang, T.GROUP_GONE))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("group-gone notice failed: {}", e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(GroupRegistry::class.java)
    }
}
