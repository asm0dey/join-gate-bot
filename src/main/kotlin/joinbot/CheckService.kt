package joinbot

import eu.vendeli.tgbot.TelegramBot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

private val SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/** A moment as people see it. */
fun shownDate(at: Instant): String = SHOWN.format(at)

private val DURATION = Regex("""(\d{1,4})([hd])""")
private const val MAX_HOURS = 365L * 24
private const val BUTTONS_PER_LIST = 50

/** A closed check's deadline list: everyone on the roster still to answer; a null profile means the lookup failed. */
private class Roll(val chatId: Long, val title: String, val deadline: Instant, val known: Int, val total: Int, val people: List<Pair<Long, Profile?>>)

/** The member check (spec §1–§5): `/remind` in the group, the deep-link entry, passed members' updates, and the deadline. */
class CheckService(
    private val groups: GroupRepo,
    private val forms: FormRepo,
    private val subs: SubmissionRepo,
    private val members: MemberRepo,
    private val checks: CheckRepo,
    private val sessions: SessionRepo,
    private val users: BotUserRepo,
    private val admins: AdminCheck,
    private val flow: ApplicantFlow,
    private val roster: Roster,
    private val bot: TelegramBot,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(CheckService::class.java)

    @Volatile private var username: String? = null

    /** Cached after the first successful getMe; null while Telegram can't tell us. */
    private suspend fun username() = username ?: bot.botUsername()?.also { username = it }

    // ponytail: one lock for every chat's /remind; per-chat locks if /remind ever gets busy
    private val remindLock = Mutex()

    /** `<n>h` or `<n>d`, 1 hour to 365 days; a missing [arg] is 7 days. Null when invalid or out of range. */
    fun parseDuration(arg: String?): Duration? {
        if (arg == null) return Duration.ofDays(7)
        val (n, unit) = DURATION.matchEntire(arg)?.destructured ?: return null
        val hours = n.toLong() * if (unit == "d") 24 else 1
        return if (hours in 1..MAX_HOURS) Duration.ofHours(hours) else null
    }

    /**
     * `/remind[@addressedTo] [arg]` from [fromId] in [chatId]; [anonymous] when posted as the group itself.
     * A command addressed to another bot, or to any name while ours is unknown, is ignored.
     */
    suspend fun remind(chatId: Long, fromId: Long, anonymous: Boolean, arg: String?, addressedTo: String? = null) {
        suspend fun reply(key: T) { bot.sendText(chatId, Texts.t(null, key)) }
        if (addressedTo != null && !addressedTo.equals(username(), ignoreCase = true)) return
        if (anonymous) return reply(T.REMIND_ANONYMOUS)
        if (!admins.canDecideCheck(chatId, fromId, fresh = true)) return
        val duration = parseDuration(arg) ?: return reply(T.REMIND_BAD_DURATION)
        if (forms.current(chatId) == null) return reply(T.REMIND_NO_FORM)
        if (!admins.botCanBan(chatId)) return reply(T.REMIND_NO_BAN)
        val name = username() ?: return reply(T.TRY_AGAIN)
        val title = groups.get(chatId)?.title.orEmpty()
        val adminLang = users.lang(fromId)
        remindLock.withLock {
            val now = clock.instant()
            val open = checks.openCheck(chatId)
            val deadline = if (open != null && arg == null) open.deadline else now + duration
            val button = Button(Texts.t(null, T.FILL_FORM), url = "https://t.me/$name?start=r$chatId")
            val sent = bot.sendText(chatId, Texts.t(null, T.REMIND_POST, shownDate(deadline)), listOf(listOf(button)))
            if (sent !is Sent.Ok) {
                bot.sendText(fromId, Texts.t(adminLang, T.REMIND_POST_FAILED, title))
                return
            }
            if (open == null) checks.open(chatId, deadline, fromId, now) else if (arg != null) checks.setDeadline(chatId, deadline)
            checks.addMessage(chatId, sent.messageId)
        }
        bot.sendText(fromId, Texts.t(adminLang, T.REMIND_KNOWS, members.count(chatId), bot.memberCount(chatId) ?: 0, title))
    }

    /** `/start r<chatId>`: spec §2 checks, in order. */
    suspend fun enter(chatId: Long, user: Profile, userId: Long, lang: String?) {
        suspend fun reply(key: T, vararg args: Any, buttons: Keyboard = emptyList()) {
            bot.sendText(userId, Texts.t(lang, key, *args), buttons)
        }
        if (checks.openCheck(chatId) == null) return reply(T.CHECK_ENDED)
        val info = bot.memberInfo(chatId, userId) ?: return reply(T.TRY_AGAIN)
        if (info.membership == Membership.GONE) return reply(T.NOT_IN_GROUP)
        roster.seen(chatId, userId)
        if (info.membership == Membership.ADMIN) return reply(T.ADMINS_EXEMPT)
        val session = sessions.get(userId, chatId)
        if (session?.step == WAITING) return reply(T.QUEUED, groups.get(chatId)?.title.orEmpty())
        if (session == null) {
            if (subs.pendingCheck(chatId, userId) != null) return reply(T.CHECK_PENDING)
            if (members.passedAt(chatId, userId) != null) {
                val yes = Button(Texts.t(lang, T.YES_UPDATE), "u|$chatId")
                return reply(T.OFFER_UPDATE, groups.get(chatId)?.title.orEmpty(), buttons = listOf(listOf(yes)))
            }
        }
        flow.startMember(chatId, userId, user, lang, Kind.CHECK) // re-asks an open session
    }

    /** Lists the user's passed groups that can take an update; false when there are none. */
    suspend fun offerUpdates(userId: Long, lang: String?): Boolean {
        val passed = members.passedGroups(userId).toSet()
        val open = groups.active().filter { it.chatId in passed && forms.current(it.chatId) != null }.sortedBy { it.title }
        if (open.isEmpty()) return false
        bot.sendText(userId, Texts.t(lang, T.UPDATE_LIST), open.map { listOf(Button(it.title, "u|${it.chatId}")) })
        return true
    }

    /** `u|<chatId>`: starts an Update session if the user is still a passed member of an active group with a form and has no session for it. */
    suspend fun onUpdateButton(userId: Long, callbackId: String, data: String, profile: Profile, lang: String?) {
        val chatId = data.removePrefix("u|").toLongOrNull()
        if (chatId == null || groups.get(chatId)?.active != true || forms.current(chatId) == null ||
            bot.memberInfo(chatId, userId)?.membership != Membership.MEMBER || members.passedAt(chatId, userId) == null ||
            sessions.get(userId, chatId) != null
        ) return bot.answerCallback(callbackId, Texts.t(lang, T.STALE_BUTTON), alert = true)
        bot.answerCallback(callbackId)
        flow.startMember(chatId, userId, profile, lang, Kind.UPDATE)
    }

    /**
     * One deadline pass (spec §5). The conditional close makes each check's list go out once, however many passes overlap.
     * Deciders and the list are settled before closing, so a failed admin lookup leaves the check open for the next pass.
     */
    suspend fun tick() {
        val now = clock.instant()
        for (c in checks.due(now)) {
            try {
                if (groups.get(c.chatId)?.active != true) { closeForChat(c.chatId); continue } // spec §7: no list
                val deciders = admins.checkDecidersOrNull(c.chatId)
                if (deciders == null) { log.warn("deadline postponed: admin lookup failed"); continue }
                val roll = roll(c)
                if (!checks.close(c.chatId, now)) continue
                closePosts(c.chatId, c.deadline, now)
                for (adminId in deciders) checks.notice(c.chatId, adminId, sendRoll(roll, adminId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("deadline failed: {}", e.javaClass.simpleName)
            }
        }
    }

    // ponytail: an hourly loop means a list lands up to an hour after the deadline
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("deadline pass failed: {}", e.javaClass.simpleName)
            }
            delay(1.hours)
        }
    }

    /** The group went inactive: the check closes without a list (spec §7). */
    suspend fun closeForChat(chatId: Long) {
        val now = clock.instant()
        val c = checks.openCheck(chatId) ?: return
        if (checks.close(chatId, now)) closePosts(chatId, c.deadline, now)
    }

    /** On /start: the deadline lists [adminId] couldn't be sent. Returns how many were sent now. */
    suspend fun deliverNotices(adminId: Long): Int {
        var sent = 0
        for (chatId in checks.undelivered(adminId)) {
            val c = checks.get(chatId) ?: continue
            // an admin who lost the rights since doesn't get the member list
            if (!admins.canDecideCheck(chatId, adminId)) { checks.notice(chatId, adminId, true); continue }
            if (!sendRoll(roll(c), adminId)) break // a 403 means the rest would fail too
            checks.notice(chatId, adminId, true)
            sent++
        }
        return sent
    }

    /**
     * `k|<chatId>|<userId>` clicked on the list message [messageId], which shows [text] and [keyboard].
     * Removes the person unless they answered since or were removed already; then drops their button from this copy only.
     */
    suspend fun onRemoveButton(adminId: Long, callbackId: String, data: String, messageId: Long, text: String, keyboard: Keyboard) {
        val lang = users.lang(adminId)
        suspend fun alert(key: T, vararg args: Any) = bot.answerCallback(callbackId, Texts.t(lang, key, *args), alert = true)
        val parts = data.split('|')
        val chatId = parts.getOrNull(1)?.toLongOrNull()
        val userId = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size != 3 || chatId == null || userId == null) return alert(T.STALE_BUTTON)
        if (!admins.canDecideCheck(chatId, adminId, fresh = true)) return alert(T.NOT_ADMIN_ANYMORE)
        val profile = bot.memberInfo(chatId, userId)?.profile
        val name = profile?.name ?: Texts.t(lang, T.ID_ONLY, userId)
        if (members.passedAt(chatId, userId) != null) return alert(T.PASSED_SINCE, name)
        if (subs.pendingCheck(chatId, userId) != null) return alert(T.PENDING_SINCE, name)
        if (!members.known(chatId, userId)) return alert(T.ALREADY_REMOVED)
        when (bot.removeMember(chatId, userId)) {
            Kick.OK, Kick.GONE -> {}
            Kick.NO_RIGHT -> return alert(T.NO_BAN_RIGHT)
            Kick.TRANSIENT -> return alert(T.TRY_AGAIN)
        }
        // two deciders clicking the same person at once may both kick; only the one that deletes the roster row records it
        if (!members.remove(chatId, userId)) return alert(T.ALREADY_REMOVED)
        sessions.delete(userId, chatId) // a form still open must not end in an approval for someone gone
        val now = clock.instant()
        subs.create(chatId, userId, null, profile ?: Profile(name, null), null, Status.REMOVED, now, Kind.CHECK, adminId, now)
        bot.answerCallback(callbackId)
        val rest = keyboard.map { row -> row.filter { it.data != data } }.filter { it.isNotEmpty() }
        bot.editText(adminId, messageId, text, rest)
    }

    /** Every post of the check loses its button and says when it closed. */
    private suspend fun closePosts(chatId: Long, deadline: Instant, at: Instant) {
        val text = Texts.t(null, T.REMIND_POST, shownDate(deadline)) + "\n\n" + Texts.t(null, T.CHECK_CLOSED, shownDate(at))
        for (messageId in checks.messages(chatId)) bot.editText(chatId, messageId, text)
    }

    /** Roster rows without a pass or a pending Check; leavers are dropped from the roster, admins skipped. */
    private suspend fun roll(c: Check): Roll {
        val people = members.unpassed(c.chatId).filter { subs.pendingCheck(c.chatId, it) == null }.mapNotNull { userId ->
            val info = bot.memberInfo(c.chatId, userId)
            when (info?.membership) {
                Membership.GONE -> { members.remove(c.chatId, userId); null }
                Membership.ADMIN -> null
                else -> userId to info?.profile // a failed lookup stays on the list, by id
            }
        }
        return Roll(c.chatId, groups.get(c.chatId)?.title.orEmpty(), c.deadline, members.count(c.chatId), bot.memberCount(c.chatId) ?: 0, people)
    }

    /** False only when Telegram refused the first message (403). */
    private suspend fun sendRoll(r: Roll, adminId: Long): Boolean {
        val lang = users.lang(adminId)
        val messages = if (r.people.isEmpty()) listOf(Texts.t(lang, T.ALL_PASSED, r.title) to emptyList<List<Button>>())
        else {
            val header = Texts.t(lang, T.NONRESPONDERS, r.title, shownDate(r.deadline), r.known, r.total)
            r.people.chunked(BUTTONS_PER_LIST).map { chunk ->
                header to chunk.map { (userId, p) ->
                    val label = p?.let { it.name + it.username?.let { u -> " @$u" }.orEmpty() } ?: Texts.t(lang, T.ID_ONLY, userId)
                    listOf(Button(Texts.t(lang, T.REMOVE_NAME, label), "k|${r.chatId}|$userId"))
                }
            }
        }
        for ((i, m) in messages.withIndex()) {
            if (bot.sendText(adminId, m.first, m.second) == Sent.Forbidden && i == 0) {
                users.forbidden(adminId)
                return false
            }
        }
        return true
    }
}
