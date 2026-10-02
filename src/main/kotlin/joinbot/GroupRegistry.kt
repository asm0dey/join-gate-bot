package joinbot

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/** Reacts to the bot being added, promoted, demoted or removed in a group, and to a group becoming a supergroup. */
class GroupRegistry(
    private val groups: GroupRepo, private val sessions: SessionRepo, private val subs: SubmissionRepo, private val users: BotUserRepo,
    private val review: ReviewService, private val flow: ApplicantFlow, private val tg: Tg,
) {
    // ponytail: global lock, bot-status changes are rare; per-chat locks if that changes
    private val lock = Mutex()

    suspend fun onBotStatus(chatId: Long, title: String, isAdmin: Boolean, canInvite: Boolean) = lock.withLock {
        val was = groups.get(chatId)?.active
        val active = isAdmin && canInvite
        groups.upsert(chatId, title, active)
        if (active) {
            if (was == false) review.resumeChat(chatId)
            return@withLock
        }
        // every my_chat_member update is a real admin action, so the reminder repeats
        if (isAdmin) tg.send(chatId, Texts.t(null, T.NEEDS_INVITE_RIGHT))
        if (was == true) handOff(chatId)
        // on every inactive update, not only the transition: idempotent, and catches sessions left in a stored-inactive group
        try {
            flow.closeForChat(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("closing forms failed: {}", e.javaClass.simpleName)
        }
    }

    /** The group became a supergroup with [newId]; everything moves there. A repeated update changes nothing. */
    suspend fun onMigrated(oldId: Long, newId: Long) = lock.withLock {
        if (groups.migrate(oldId, newId, sessions)) log.info("group moved to its supergroup")
    }

    /** One-shot on active → inactive, so it runs before the retryable closeForChat. Nothing is approved or declined. */
    private suspend fun handOff(chatId: Long) {
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
