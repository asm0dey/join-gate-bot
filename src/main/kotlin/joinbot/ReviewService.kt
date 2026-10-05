package joinbot

import eu.vendeli.tgbot.TelegramBot
import java.time.Clock
import java.time.Duration

/** Telegram's message length limit. */
const val MAX_MESSAGE = 4096

/**
 * The applicant became unreachable mid-form: no answers, or a field of the pinned [form] has no key
 * (submit stores every field id, "" when skipped). Without the form, only null answers tell.
 */
fun Submission.partial(form: Form?) = answers == null || form?.fields.orEmpty().any { it.id !in answers }

/** Fans a submission out to the group's deciders and settles Approve/Reject clicks; the first click wins (spec C). */
class ReviewService(
    private val subs: SubmissionRepo, private val forms: FormRepo, private val groups: GroupRepo, private val users: BotUserRepo, private val members: MemberRepo,
    private val admins: AdminCheck, private val bot: TelegramBot, private val clock: Clock,
) {
    /** Tells the Join deciders about a saved update: a copy without buttons, not recorded as a review message. */
    suspend fun notifyUpdate(submissionId: Long) {
        val s = subs.get(submissionId) ?: return
        if (groups.get(s.chatId)?.active != true) return
        val form = s.formVersion?.let { forms.version(s.chatId, it) }
        for (adminId in admins.deciders(s.chatId)) sendCopy(s, form, adminId)
    }

    suspend fun submit(submissionId: Long) {
        val s = subs.get(submissionId) ?: return
        val g = groups.get(s.chatId)?.takeIf { it.active } ?: return
        val form = s.formVersion?.let { forms.version(s.chatId, it) }
        var reached = 0
        // no dm_ok pre-check: an admin who joined through the form wrote to the bot but never sent /start; a 403 tells instead
        for (adminId in if (s.kind == Kind.CHECK) admins.checkDeciders(s.chatId) else admins.deciders(s.chatId)) {
            if (sendCopy(s, form, adminId)) reached++
        }
        if (reached > 0) return
        val now = clock.instant()
        if (g.nudgedAt != null && now < g.nudgedAt.plus(NUDGE_EVERY)) return
        bot.sendText(s.chatId, Texts.t(null, T.NUDGE, subs.list(s.chatId, Status.PENDING).size))
        groups.markNudged(s.chatId, now)
    }

    suspend fun onDecision(adminId: Long, callbackId: String, data: String) {
        val lang = users.lang(adminId)
        suspend fun alert(key: T, vararg args: Any) = bot.answerCallback(callbackId, Texts.t(lang, key, *args), alert = true)

        val parts = data.split('|')
        val status = when (parts.getOrNull(2)) { "a" -> Status.APPROVED; "j" -> Status.REJECTED; else -> null }
        val s = parts.getOrNull(1)?.toLongOrNull()?.let(subs::get)
        if (parts.size != 3 || parts[0] != "r" || status == null || s == null) return alert(T.STALE_BUTTON)
        if (groups.get(s.chatId)?.active != true) return alert(T.REVIEW_SUSPENDED)
        val check = s.kind == Kind.CHECK
        val allowed = if (check) admins.canDecideCheck(s.chatId, adminId, fresh = true) else admins.canDecide(s.chatId, adminId, fresh = true)
        if (!allowed) return alert(T.NOT_ADMIN_ANYMORE)

        // The conditional UPDATE is the only arbiter between simultaneous clicks.
        suspend fun alreadyDecided() = alert(T.ALREADY_DECIDED, deciderName(s.chatId, subs.get(s.id)?.decidedBy, lang))
        if (!subs.decide(s.id, status, adminId, clock.instant())) return alreadyDecided()
        var revertAlert = T.TRY_AGAIN
        val result = when {
            // approving a check needs no Telegram call: the member is already in
            check && status == Status.APPROVED -> Decision.OK
            check -> when (bot.removeMember(s.chatId, s.userId)) {
                Kick.OK -> Decision.OK
                Kick.GONE -> Decision.GONE
                Kick.NO_RIGHT -> { revertAlert = T.NO_BAN_RIGHT; Decision.TRANSIENT }
                Kick.TRANSIENT -> Decision.TRANSIENT
            }
            status == Status.APPROVED -> bot.approveJoin(s.chatId, s.userId)
            else -> bot.declineJoin(s.chatId, s.userId)
        }
        val final = when (result) {
            Decision.OK -> status
            Decision.TRANSIENT -> { subs.revert(s.id, status, adminId); return alert(revertAlert) }
            // one conditional step: a revert first would let another click win in between
            Decision.GONE -> {
                if (!subs.transition(s.id, status, adminId, Status.WITHDRAWN)) return alreadyDecided()
                Status.WITHDRAWN
            }
        }
        if (final == Status.APPROVED) members.pass(s.chatId, s.userId, clock.instant())
        if (check && final == Status.REJECTED) members.remove(s.chatId, s.userId)
        bot.answerCallback(callbackId)

        val form = s.formVersion?.let { forms.version(s.chatId, it) }
        for ((copyAdmin, messageId) in subs.reviewMessages(s.id)) {
            val l = users.lang(copyAdmin)
            val outcome = when (final) {
                Status.APPROVED -> Texts.t(l, T.DECIDED_BY_APPROVED, deciderName(s.chatId, adminId, l))
                Status.REJECTED -> Texts.t(l, if (check) T.DECIDED_BY_REMOVED else T.DECIDED_BY_REJECTED, deciderName(s.chatId, adminId, l))
                else -> Texts.t(l, T.WITHDRAWN)
            }
            bot.editText(copyAdmin, messageId, fit(renderReview(s, form, l), "\n\n$outcome"))
        }
        if (final == Status.WITHDRAWN) return
        val userLang = users.lang(s.userId)
        val approved = final == Status.APPROVED
        val userText = if (check) {
            Texts.t(userLang, if (approved) T.CHECK_APPROVED_USER else T.CHECK_REMOVED_USER, groups.get(s.chatId)?.title.orEmpty())
        } else Texts.t(userLang, if (approved) T.APPROVED_USER else T.REJECTED_USER)
        val sent = bot.sendText(s.userId, userText)
        if (sent == Sent.Forbidden) users.forbidden(s.userId)
    }

    /** On /start: every PENDING submission in an active group [adminId] can decide, minus copies they already have. Returns how many were sent. */
    suspend fun deliverPending(adminId: Long): Int {
        // ponytail: one cached admin lookup per active group; track chat_member updates if groups grow into the hundreds
        val join = groups.active().map { it.chatId }.filter { admins.canDecide(it, adminId) }.toSet()
        val check = groups.active().map { it.chatId }.filter { admins.canDecideCheck(it, adminId) }.toSet()
        var sent = 0
        for (s in subs.pendingInChats((join + check).toList())) {
            if (s.chatId !in (if (s.kind == Kind.CHECK) check else join)) continue
            if (subs.reviewMessages(s.id).any { it.first == adminId }) continue
            if (sendCopy(s, s.formVersion?.let { forms.version(s.chatId, it) }, adminId)) sent++
            else if (!users.dmOk(adminId)) break // a 403 means the rest would fail too
        }
        return sent
    }

    /** The bot lost the invite right: every review copy of a PENDING submission in [chatId] loses its buttons. */
    suspend fun suspendChat(chatId: Long) {
        for (s in subs.list(chatId, Status.PENDING)) {
            val form = s.formVersion?.let { forms.version(chatId, it) }
            for ((adminId, messageId) in subs.reviewMessages(s.id)) {
                val l = users.lang(adminId)
                bot.editText(adminId, messageId, fit(renderReview(s, form, l), "\n\n" + Texts.t(l, T.REVIEW_SUSPENDED)))
            }
        }
    }

    /** The right is back: fresh copies with buttons for every PENDING submission; old copies were suspended, so none is skipped. */
    suspend fun resumeChat(chatId: Long) = subs.list(chatId, Status.PENDING).forEach { submit(it.id) }

    fun renderReview(s: Submission, form: Form?, lang: String?): String = buildString {
        val header = when (s.kind) { Kind.JOIN -> T.REVIEW_HEADER; Kind.CHECK -> T.CHECK_HEADER; Kind.UPDATE -> T.UPDATE_HEADER }
        append(Texts.t(lang, header, s.profile.name, groups.get(s.chatId)?.title.orEmpty()))
        s.profile.username?.let { append("\n@").append(it) }
        append("\n\n")
        val partial = s.partial(form)
        if (partial) append(Texts.t(lang, T.REVIEW_UNREACHABLE, s.profile.name))
        val answers = s.answers ?: return@buildString
        if (partial) append("\n\n")
        // form order; ids the form no longer knows (or a missing form) fall back to the raw id
        val prompts = form?.fields.orEmpty().associate { it.id to it.prompt }
        val ids = prompts.keys.filter { it in answers } + answers.keys.filter { it !in prompts }
        append(ids.joinToString("\n") { "${prompts[it] ?: it}: ${answers[it]}" })
    }

    /** False when the admin could not be reached. */
    private suspend fun sendCopy(s: Submission, form: Form?, adminId: Long): Boolean {
        val lang = users.lang(adminId)
        val no = if (s.kind == Kind.CHECK) T.REMOVE else T.REJECT
        // an update is informational: no buttons, and no review message to edit later
        val buttons = if (s.kind == Kind.UPDATE) emptyList()
        else listOf(listOf(Button(Texts.t(lang, T.APPROVE), "r|${s.id}|a"), Button(Texts.t(lang, no), "r|${s.id}|j")))
        return when (val r = bot.sendText(adminId, fit(renderReview(s, form, lang)), buttons)) {
            is Sent.Ok -> { if (s.kind != Kind.UPDATE) subs.addReviewMessage(s.id, adminId, r.messageId); true }
            Sent.Forbidden -> { users.forbidden(adminId); false }
            Sent.Failed -> false
        }
    }

    private suspend fun deciderName(chatId: Long, userId: Long?, lang: String?): String =
        userId?.let { admins.name(chatId, it) } ?: Texts.t(lang, T.AN_ADMIN)

    private companion object {
        val NUDGE_EVERY: Duration = Duration.ofHours(1)

        /** [body] cut at a line boundary, ending in "…", so that body + [suffix] fits one message. Full answers stay in the Mini App. */
        fun fit(body: String, suffix: String = ""): String {
            val room = MAX_MESSAGE - suffix.length
            if (body.length <= room) return body + suffix
            val head = body.take(room - 1)
            return head.take(head.lastIndexOf('\n') + 1) + "…" + suffix
        }
    }
}
