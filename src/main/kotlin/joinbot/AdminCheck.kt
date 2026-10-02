package joinbot

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Who may review join requests: non-bot admins with the invite right. Admin lists are cached for [ttl]. */
class AdminCheck(private val tg: Tg, private val clock: Clock, private val ttl: Duration = Duration.ofSeconds(60)) {
    private val cache = ConcurrentHashMap<Long, Pair<Instant, List<Long>>>()

    suspend fun canDecide(chatId: Long, userId: Long, fresh: Boolean = false): Boolean = userId in load(chatId, fresh)

    suspend fun deciders(chatId: Long): List<Long> = load(chatId, false)

    // A failed lookup is not cached: nobody can decide now, the next call retries.
    private suspend fun load(chatId: Long, fresh: Boolean): List<Long> {
        val now = clock.instant()
        if (!fresh) cache[chatId]?.takeIf { now < it.first.plus(ttl) }?.let { return it.second }
        val ids = tg.admins(chatId)?.filter { !it.isBot && it.canInvite }?.map { it.userId } ?: return emptyList()
        cache[chatId] = now to ids
        return ids
    }
}
