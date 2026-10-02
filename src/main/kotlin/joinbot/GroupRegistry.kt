package joinbot

import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

/** Reacts to the bot being added, promoted, demoted or removed in a group. */
class GroupRegistry(
    private val groups: GroupRepo, private val subs: SubmissionRepo, private val users: BotUserRepo,
    private val review: ReviewService, private val flow: ApplicantFlow, private val tg: Tg,
) {
    suspend fun onBotStatus(chatId: Long, title: String, isAdmin: Boolean, canInvite: Boolean) {
        val was = groups.get(chatId)?.active
        val active = isAdmin && canInvite
        groups.upsert(chatId, title, active)
        if (active) {
            if (was == false) review.resumeChat(chatId)
            return
        }
        // every my_chat_member update is a real admin action, so the reminder repeats
        if (isAdmin) tg.send(chatId, Texts.t(null, T.NEEDS_INVITE_RIGHT))
        // on every inactive update, not only the transition: idempotent, and catches sessions left in a stored-inactive group
        flow.closeForChat(chatId)
        if (was != true) return

        // nothing is approved or declined: the requests stay in Telegram's Join requests list
        val pending = subs.list(chatId, Status.PENDING)
        for (userId in pending.map { it.userId }.distinct()) {
            try {
                tg.send(userId, Texts.t(users.lang(userId), T.MANUAL_REVIEW))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("manual-review notice failed: {}", e.javaClass.simpleName)
            }
        }
        review.suspendChat(chatId)
        if (pending.isNotEmpty()) tg.send(chatId, Texts.t(null, T.GROUP_HANDOFF, pending.size))
    }

    private companion object {
        val log = LoggerFactory.getLogger(GroupRegistry::class.java)
    }
}
