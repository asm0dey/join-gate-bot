package joinbot

import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

private val IDLE_LIMIT = Duration.ofDays(7)
private val PERIOD = 24.hours

/** Expires idle sessions and deletes submissions past each group's retention. */
class Purge(
    private val sessions: SessionRepo, private val flow: ApplicantFlow, private val subs: SubmissionRepo,
    private val groups: GroupRepo, private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Purge::class.java)

    suspend fun runOnce() {
        val now = clock.instant()
        val cutoff = now - IDLE_LIMIT
        // active sessions first: expiring one starts the user's next waiting form, which then is no longer idle
        for (idle in sessions.idleSince(cutoff).sortedBy { it.step == WAITING }) {
            try {
                // re-read: expiring an earlier session may have just started this one
                val s = sessions.get(idle.userId, idle.chatId)?.takeIf { it.touchedAt < cutoff } ?: continue
                subs.create(s.chatId, s.userId, s.formVersion, s.state.profile, null, Status.EXPIRED, now)
                flow.decline(s, T.EXPIRED, startNext = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("expiry failed: {}", e.javaClass.simpleName)
            }
        }
        // inactive groups too: their submissions still age out, PENDING ones included, as nobody can decide them there
        for (g in groups.all()) {
            try {
                subs.deleteOlderThan(g.chatId, now - Duration.ofDays(g.retentionDays.toLong()), keepPending = g.active)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("retention purge failed: {}", e.javaClass.simpleName)
            }
        }
    }

    // ponytail: a daily loop means expiry lands up to 8 days after the last activity; run it hourly if that matters
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            try {
                runOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("purge failed: {}", e.javaClass.simpleName)
            }
            delay(PERIOD)
        }
    }
}
