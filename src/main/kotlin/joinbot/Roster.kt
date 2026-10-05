package joinbot

import java.util.concurrent.ConcurrentHashMap

/** Who the bot has seen in each group; the cache keeps the busy-chat hot path off the database. */
class Roster(private val members: MemberRepo) {
    // ponytail: unbounded, bounded LRU if memory shows up
    private val cache = ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    fun seen(chatId: Long, userId: Long) {
        if (chatId to userId in cache) return
        members.seen(chatId, userId)
        cache.add(chatId to userId) // after the write: a failed insert must not be remembered
    }

    fun left(chatId: Long, userId: Long) {
        members.remove(chatId, userId)
        cache.remove(chatId to userId)
    }
}
