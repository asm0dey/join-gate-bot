package joinbot

import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val IDLE_LIMIT = Duration.ofDays(7)
private val PERIOD = Duration.ofHours(24)

/** Expires idle sessions and deletes submissions past each group's retention. */
class Purge(
    private val sessions: SessionRepo, private val flow: ApplicantFlow, private val subs: SubmissionRepo,
    private val groups: GroupRepo, private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(Purge::class.java)

    suspend fun runOnce() {
        val now = clock.instant()
        for (s in sessions.idleSince(now - IDLE_LIMIT)) {
            try {
                subs.create(s.chatId, s.userId, s.formVersion, s.state.profile, null, Status.EXPIRED, now)
                flow.decline(s, T.EXPIRED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("expiry failed: {}", e.javaClass.simpleName)
            }
        }
        for (g in groups.active()) subs.deleteOlderThan(g.chatId, now - Duration.ofDays(g.retentionDays.toLong()))
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
            delay(PERIOD.toMillis())
        }
    }
}
