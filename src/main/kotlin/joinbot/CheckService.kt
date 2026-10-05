package joinbot

import eu.vendeli.tgbot.TelegramBot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

/** A moment as people see it. */
fun shownDate(at: Instant): String = SHOWN.format(at)

private val DURATION = Regex("""(\d{1,4})([hd])""")
private const val MAX_HOURS = 365L * 24

/** The member check (spec §1–§2): `/remind` in the group, the deep-link entry, and passed members' updates. */
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
    @Volatile private var username: String? = null

    // ponytail: one lock for every chat's /remind; per-chat locks if /remind ever gets busy
    private val remindLock = Mutex()

    /** `<n>h` or `<n>d`, 1 hour to 365 days; a missing [arg] is 7 days. Null when invalid or out of range. */
    fun parseDuration(arg: String?): Duration? {
        if (arg == null) return Duration.ofDays(7)
        val (n, unit) = DURATION.matchEntire(arg)?.destructured ?: return null
        val hours = n.toLong() * if (unit == "d") 24 else 1
        return if (hours in 1..MAX_HOURS) Duration.ofHours(hours) else null
    }

    /** `/remind [arg]` from [fromId] in [chatId]; [anonymous] when posted as the group itself. */
    suspend fun remind(chatId: Long, fromId: Long, anonymous: Boolean, arg: String?) {
        suspend fun reply(key: T) { bot.sendText(chatId, Texts.t(null, key)) }
        if (anonymous) return reply(T.REMIND_ANONYMOUS)
        if (!admins.canDecideCheck(chatId, fromId, fresh = true)) return
        val duration = parseDuration(arg) ?: return reply(T.REMIND_BAD_DURATION)
        if (forms.current(chatId) == null) return reply(T.REMIND_NO_FORM)
        if (!admins.botCanBan(chatId)) return reply(T.REMIND_NO_BAN)
        val name = username ?: bot.botUsername()?.also { username = it } ?: return reply(T.TRY_AGAIN)
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
        if (sessions.get(userId, chatId) == null) {
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

    /** `u|<chatId>`: starts an Update session if the user is still a passed member of an active group with a form. */
    suspend fun onUpdateButton(userId: Long, callbackId: String, data: String, profile: Profile, lang: String?) {
        val chatId = data.removePrefix("u|").toLongOrNull()
        if (chatId == null || groups.get(chatId)?.active != true || forms.current(chatId) == null ||
            bot.memberInfo(chatId, userId)?.membership != Membership.MEMBER || members.passedAt(chatId, userId) == null
        ) return bot.answerCallback(callbackId, Texts.t(lang, T.STALE_BUTTON), alert = true)
        bot.answerCallback(callbackId)
        flow.startMember(chatId, userId, profile, lang, Kind.UPDATE)
    }
}
