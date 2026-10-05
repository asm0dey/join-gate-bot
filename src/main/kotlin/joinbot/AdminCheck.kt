package joinbot

import eu.vendeli.tgbot.TelegramBot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Who may review join requests: non-bot admins with the invite right. Admin lists are cached for [ttl]. */
class AdminCheck(
    private val bot: TelegramBot,
    private val clock: Clock,
    private val botId: Long,
    private val ttl: Duration = Duration.ofSeconds(60),
) {
    private val cache = ConcurrentHashMap<Long, Pair<Instant, List<Admin>>>()

    suspend fun canDecide(chatId: Long, userId: Long, fresh: Boolean = false): Boolean = userId in load(chatId, fresh).deciders()

    suspend fun deciders(chatId: Long): List<Long> = load(chatId, false).deciders()

    /** Who may start and manage a member check: non-bot admins with Invite users and Ban users. */
    suspend fun canDecideCheck(chatId: Long, userId: Long, fresh: Boolean = false): Boolean =
        userId in load(chatId, fresh).checkDeciders()

    suspend fun checkDeciders(chatId: Long): List<Long> = load(chatId, false).checkDeciders()

    /** Whether the bot's own admin entry has Ban users. */
    suspend fun botCanBan(chatId: Long): Boolean = load(chatId, false).any { it.userId == botId && it.canBan }

    /** First name of any admin of the chat, null if not (or no longer) one. */
    suspend fun name(chatId: Long, userId: Long): String? = load(chatId, false).find { it.userId == userId }?.name

    private fun List<Admin>.deciders() = filter { !it.isBot && it.canInvite }.map { it.userId }

    private fun List<Admin>.checkDeciders() = filter { !it.isBot && it.canInvite && it.canBan }.map { it.userId }

    // A failed lookup is not cached: nobody can decide now, the next call retries.
    private suspend fun load(chatId: Long, fresh: Boolean): List<Admin> {
        val now = clock.instant()
        if (!fresh) cache[chatId]?.takeIf { now < it.first.plus(ttl) }?.let { return it.second }
        val admins = bot.chatAdmins(chatId) ?: return emptyList()
        cache[chatId] = now to admins
        return admins
    }
}
