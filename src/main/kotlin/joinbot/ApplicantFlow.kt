package joinbot

import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

/** Max chars per summary message; the summary is split at line boundaries. */
const val SUMMARY_CHUNK = 3500

/** Callback data for an applicant button (spec B2); under 64 bytes for any valid form. */
fun cbData(chatId: Long, step: Int, action: Char, idx: Int = 0) = "f|$chatId|$step|$action|$idx"

/**
 * The applicant's form, one question at a time (spec B). The `form_session` row is the whole state:
 * step 0 until fields.size - 1 is a question, fields.size is the summary, [WAITING] is queued behind another group.
 */
class ApplicantFlow(
    private val groups: GroupRepo, private val forms: FormRepo, private val sessions: SessionRepo, private val subs: SubmissionRepo,
    private val users: BotUserRepo, private val review: ReviewService, private val tg: Tg, private val clock: Clock,
) {
    suspend fun onJoinRequest(chatId: Long, userId: Long, userChatId: Long, profile: Profile, lang: String?) {
        if (groups.get(chatId)?.active != true) return
        val (version, _) = forms.current(chatId) ?: return
        if (subs.pendingFor(chatId, userId) != null) return
        val active = sessions.active(userId)
        val s = Session(userId, chatId, version, 0, SessionState(profile), lang, clock.instant())
        if (active != null && active.chatId != chatId) sessions.put(s.copy(step = WAITING))
        else begin(s, userChatId)
    }

    /** False when [userId] has no active session. */
    suspend fun onMessage(userId: Long, text: String?, lang: String?): Boolean {
        val s = sessions.active(userId)?.withLang(lang) ?: return false
        val form = formOf(s) ?: return false
        val field = form.fields.getOrNull(s.step)
        val buttonOnly = field == null || field is Multi || field is Consent || (field is Radio && !s.state.otherMode)
        if (text == null || buttonOnly) return reask(s, form, Reason.WRONG_KIND)
        when (val c = validate(field, Input.Typed(text))) {
            is Check.Ok -> advance(s, form, c.value)
            is Check.Invalid -> reask(s, form, c.reason)
        }
        return true
    }

    /** [messageId] is the message the button sits on; its keyboard is edited. */
    suspend fun onCallback(userId: Long, callbackId: String, data: String, lang: String?, messageId: Long) {
        suspend fun stale() = tg.answer(callbackId, Texts.t(lang, T.STALE_BUTTON), alert = true)
        val p = data.split('|')
        val chatId = p.getOrNull(1)?.toLongOrNull(); val step = p.getOrNull(2)?.toIntOrNull()
        val action = p.getOrNull(3)?.singleOrNull(); val idx = p.getOrNull(4)?.toIntOrNull()
        if (p.size != 5 || p[0] != "f" || chatId == null || step == null || action == null || idx == null) return stale()
        val s = sessions.get(userId, chatId)?.takeIf { it.step == step && it.step != WAITING }?.withLang(lang) ?: return stale()
        val form = formOf(s) ?: return stale()

        if (action == 'S' || action == 'R') {
            if (step != form.fields.size) return stale()
            tg.answer(callbackId)
            tg.edit(userId, messageId, summary(s, form).last())
            if (action == 'S') submit(s, form) else begin(s, userId)
            return
        }
        val field = form.fields.getOrNull(step) ?: return stale()
        val input = when {
            action == 'p' && field is Radio -> Input.Picked(idx)
            action == 'd' && field is Multi -> Input.PickedMany(s.state.picks)
            action == 'y' && field is Consent -> Input.Picked(0)
            action == 's' -> Input.Skip
            action == 'o' && field is Radio && field.other -> {
                put(s.copy(state = s.state.copy(otherMode = true)))
                tg.answer(callbackId); tg.edit(userId, messageId, field.prompt)
                tg.send(userId, Texts.t(s.lang, T.TYPE_OTHER))
                return
            }
            action == 't' && field is Multi && idx in field.options.indices -> {
                val picks = s.state.picks.let { if (idx in it) it - idx else it + idx }
                val next = s.copy(state = s.state.copy(picks = picks))
                put(next)
                tg.answer(callbackId); tg.edit(userId, messageId, field.prompt, keyboard(next, field))
                return
            }
            action == 'n' && field is Consent -> {
                tg.answer(callbackId); tg.edit(userId, messageId, field.prompt)
                decline(s, T.DECLINED_CONSENT)
                startNext(userId)
                return
            }
            else -> return stale()
        }
        when (val c = validate(field, input)) {
            is Check.Ok -> { tg.answer(callbackId); tg.edit(userId, messageId, field.prompt); advance(s, form, c.value) }
            // a skip on a required field or an unknown option only comes from a forged button
            is Check.Invalid -> if (c.reason == Reason.TOO_FEW || c.reason == Reason.TOO_MANY)
                tg.answer(callbackId, Texts.t(s.lang, c.reason.text()), alert = true) else stale()
        }
    }

    /** True when an active session was re-asked. */
    suspend fun onStart(userId: Long, lang: String?): Boolean {
        val s = sessions.active(userId)?.withLang(lang) ?: return false
        val form = formOf(s) ?: return false
        put(s)
        ask(s, form)
        return true
    }

    /** Ends [s]: forgets it, declines the join request, tells the user [key]. Purge uses it too. */
    suspend fun decline(s: Session, key: T) {
        sessions.delete(s.userId, s.chatId)
        try {
            tg.decline(s.chatId, s.userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("decline failed: {}", e.javaClass.simpleName)
        }
        tg.send(s.userId, Texts.t(s.lang, key))
    }

    private fun Session.withLang(lang: String?) = if (lang == null) this else copy(lang = lang)

    private fun formOf(s: Session) = forms.version(s.chatId, s.formVersion)

    private fun fresh(s: Session) = s.copy(step = 0, state = SessionState(s.state.profile))

    private fun put(s: Session) = sessions.put(s.copy(touchedAt = clock.instant()))

    /** Stores [s] at step 0 and sends welcome + question 1 to [dest]. If that fails the reviewers decide without answers. */
    private suspend fun begin(s: Session, dest: Long): Boolean {
        val form = formOf(s) ?: return false
        val start = fresh(s)
        put(start)
        var welcome = form.welcome
        if (form.fields.any { !it.required }) welcome += "\n\n" + Texts.t(s.lang, T.WELCOME_SKIP)
        if (tg.send(dest, welcome) !is Sent.Ok) {
            unreachable(start)
            return false
        }
        ask(start, form, dest)
        return true
    }

    private suspend fun unreachable(s: Session) {
        users.forbidden(s.userId)
        sessions.delete(s.userId, s.chatId)
        val id = subs.create(s.chatId, s.userId, s.formVersion, s.state.profile, null, Status.PENDING, clock.instant())
        review.submit(id)
    }

    /** Starts the oldest waiting session; one the user can't be reached for goes to reviewers without answers. */
    private suspend fun startNext(userId: Long) {
        while (sessions.active(userId) == null) {
            val next = sessions.forUser(userId).filter { it.step == WAITING }.minByOrNull { it.touchedAt } ?: return
            if (begin(next, userId)) return
        }
    }

    private suspend fun advance(s: Session, form: Form, value: String) {
        val id = form.fields[s.step].id
        val next = s.copy(step = s.step + 1, state = s.state.copy(answers = s.state.answers + (id to value), picks = emptySet(), otherMode = false))
        put(next)
        ask(next, form)
    }

    private suspend fun reask(s: Session, form: Form, reason: Reason): Boolean {
        tg.send(s.userId, Texts.t(s.lang, reason.text()))
        ask(s, form)
        return true
    }

    private suspend fun submit(s: Session, form: Form) {
        val answers = form.fields.associate { it.id to s.state.answers[it.id].orEmpty() }
        val id = subs.create(s.chatId, s.userId, s.formVersion, s.state.profile, answers, Status.PENDING, clock.instant())
        sessions.delete(s.userId, s.chatId)
        tg.send(s.userId, Texts.t(s.lang, T.SUBMITTED))
        review.submit(id)
        startNext(s.userId)
    }

    /** Current question, or the summary once every field is answered. */
    private suspend fun ask(s: Session, form: Form, dest: Long = s.userId) {
        val field = form.fields.getOrNull(s.step)
        if (field != null) { tg.send(dest, field.prompt, keyboard(s, field)); return }
        val chunks = summary(s, form)
        chunks.dropLast(1).forEach { tg.send(dest, it) }
        tg.send(dest, chunks.last(), listOf(listOf(button(s, T.SUBMIT, 'S'), button(s, T.START_OVER, 'R'))))
    }

    private fun button(s: Session, key: T, action: Char, idx: Int = 0) = Button(Texts.t(s.lang, key), cbData(s.chatId, s.step, action, idx))

    private fun keyboard(s: Session, field: Field): List<List<Button>> {
        fun opt(label: String, action: Char, idx: Int) = listOf(Button(label, cbData(s.chatId, s.step, action, idx)))
        val rows = when (field) {
            is Radio -> field.options.mapIndexed { i, o -> opt(o, 'p', i) } +
                if (field.other) listOf(listOf(button(s, T.OTHER, 'o'))) else emptyList()
            is Multi -> field.options.mapIndexed { i, o -> opt(if (i in s.state.picks) "✓ $o" else o, 't', i) } +
                listOf(listOf(button(s, T.DONE, 'd')))
            is Consent -> listOf(listOf(button(s, T.AGREE, 'y')), listOf(button(s, T.DISAGREE, 'n')))
            else -> emptyList()
        }
        return if (field.required) rows else rows + listOf(listOf(button(s, T.SKIP, 's')))
    }

    /** "prompt: answer" per field ("—" when skipped), split into messages of at most [SUMMARY_CHUNK] chars at line boundaries. */
    private fun summary(s: Session, form: Form): List<String> {
        val text = (listOf(Texts.t(s.lang, T.SUMMARY_HEADER)) +
            form.fields.map { "${it.prompt}: ${s.state.answers[it.id].orEmpty().ifEmpty { "—" }}" }).joinToString("\n")
        // a single line longer than a chunk is cut hard
        val lines = text.split('\n').flatMap { it.chunked(SUMMARY_CHUNK) }
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (l in lines) {
            if (cur.isNotEmpty() && cur.length + 1 + l.length > SUMMARY_CHUNK) { chunks += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(l)
        }
        return chunks + cur.toString()
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApplicantFlow::class.java)
    }
}
