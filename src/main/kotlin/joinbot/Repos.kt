package joinbot

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

@Serializable data class Profile(val name: String, val username: String?)
enum class Status { PENDING, APPROVED, REJECTED, WITHDRAWN, EXPIRED }
data class Group(val chatId: Long, val title: String, val active: Boolean, val retentionDays: Int, val nudgedAt: Instant?)
// profile: submission.profile is NOT NULL, and submit/expiry happen long after the join request
@Serializable data class SessionState(
    val profile: Profile,
    val answers: Map<String, String> = emptyMap(),
    val picks: Set<Int> = emptySet(),
    val otherMode: Boolean = false,
)
data class Session(val userId: Long, val chatId: Long, val formVersion: Int, val step: Int, val state: SessionState, val lang: String?, val touchedAt: Instant)
data class Submission(
    val id: Long, val chatId: Long, val userId: Long, val formVersion: Int?, val profile: Profile,
    val answers: Map<String, String>?, val status: Status, val decidedBy: Long?, val decidedAt: Instant?, val createdAt: Instant,
)

/** Step of a queued session (spec B6). */
const val WAITING = -1

private val json = Json { ignoreUnknownKeys = true }

class GroupRepo(private val db: Database) {
    fun upsert(chatId: Long, title: String, active: Boolean) = transaction(db) {
        GroupChats.upsert(onUpdateExclude = listOf(GroupChats.retentionDays, GroupChats.nudgedAt)) {
            it[GroupChats.chatId] = chatId; it[GroupChats.title] = title; it[GroupChats.active] = active
        }
        Unit
    }

    fun get(chatId: Long): Group? = transaction(db) {
        GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()?.let(::group)
    }

    fun active(): List<Group> = transaction(db) {
        GroupChats.selectAll().where { GroupChats.active eq true }.map(::group)
    }

    fun setRetention(chatId: Long, days: Int) = transaction(db) {
        GroupChats.update({ GroupChats.chatId eq chatId }) { it[retentionDays] = days }
        Unit
    }

    fun markNudged(chatId: Long, at: Instant) = transaction(db) {
        GroupChats.update({ GroupChats.chatId eq chatId }) { it[nudgedAt] = at }
        Unit
    }

    private fun group(r: ResultRow) =
        Group(r[GroupChats.chatId], r[GroupChats.title], r[GroupChats.active], r[GroupChats.retentionDays], r[GroupChats.nudgedAt])
}

class FormRepo(private val db: Database) {
    fun current(chatId: Long): Pair<Int, Form>? = transaction(db) {
        Forms.selectAll().where { Forms.chatId eq chatId }.orderBy(Forms.version to org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(1).singleOrNull()?.let(::row)
    }

    fun version(chatId: Long, v: Int): Form? = transaction(db) {
        Forms.selectAll().where { (Forms.chatId eq chatId) and (Forms.version eq v) }.singleOrNull()
            ?.let { FormJson.decodeFromString<Form>(it[Forms.schemaJson]) }
    }

    fun all(chatId: Long): List<Pair<Int, Form>> = transaction(db) {
        Forms.selectAll().where { Forms.chatId eq chatId }.orderBy(Forms.version).map(::row)
    }

    /** New version, or null when [baseVersion] is not the current one (0 = none yet). */
    fun save(chatId: Long, form: Form, baseVersion: Int, by: Long, at: Instant): Int? = transaction(db) {
        val max = Forms.version.max()
        val cur = Forms.select(max).where { Forms.chatId eq chatId }.single()[max] ?: 0
        if (cur != baseVersion) return@transaction null
        // a concurrent save of the same base loses the PK race: insertIgnore yields no row
        val inserted = Forms.insertIgnore {
            it[Forms.chatId] = chatId; it[version] = cur + 1
            it[schemaJson] = FormJson.encodeToString(form); it[updatedBy] = by; it[updatedAt] = at
        }.insertedCount
        if (inserted == 1) cur + 1 else null
    }

    private fun row(r: ResultRow) = r[Forms.version] to FormJson.decodeFromString<Form>(r[Forms.schemaJson])
}

class SessionRepo(private val db: Database, private val crypto: Crypto) {
    private fun aad(user: Long, chat: Long) = "form_session|$user|$chat"

    fun get(userId: Long, chatId: Long): Session? = transaction(db) {
        FormSessions.selectAll().where { (FormSessions.userId eq userId) and (FormSessions.chatId eq chatId) }
            .singleOrNull()?.let(::session)
    }

    fun forUser(userId: Long): List<Session> = transaction(db) {
        FormSessions.selectAll().where { FormSessions.userId eq userId }.map(::session)
    }

    /** The one session being filled in (step >= 0). */
    fun active(userId: Long): Session? = transaction(db) {
        FormSessions.selectAll().where { (FormSessions.userId eq userId) and (FormSessions.step greaterEq 0) }
            .firstOrNull()?.let(::session)
    }

    fun put(s: Session) = transaction(db) {
        val sealed = crypto.seal(json.encodeToString(s.state), aad(s.userId, s.chatId))
        FormSessions.upsert(onUpdateExclude = listOf(FormSessions.startedAt)) {
            it[userId] = s.userId; it[chatId] = s.chatId; it[formVersion] = s.formVersion; it[step] = s.step
            it[answers] = sealed; it[lang] = s.lang; it[startedAt] = s.touchedAt; it[touchedAt] = s.touchedAt
        }
        Unit
    }

    fun delete(userId: Long, chatId: Long) = transaction(db) {
        FormSessions.deleteWhere { (FormSessions.userId eq userId) and (FormSessions.chatId eq chatId) }
        Unit
    }

    fun forChat(chatId: Long): List<Session> = transaction(db) {
        FormSessions.selectAll().where { FormSessions.chatId eq chatId }.map(::session)
    }

    fun idleSince(cutoff: Instant): List<Session> = transaction(db) {
        FormSessions.selectAll().where { FormSessions.touchedAt less cutoff }.map(::session)
    }

    private fun session(r: ResultRow): Session {
        val u = r[FormSessions.userId]; val c = r[FormSessions.chatId]
        return Session(u, c, r[FormSessions.formVersion], r[FormSessions.step],
            json.decodeFromString(crypto.open(r[FormSessions.answers], aad(u, c))), r[FormSessions.lang], r[FormSessions.touchedAt])
    }
}

class SubmissionRepo(private val db: Database, private val crypto: Crypto) {
    private fun aad(id: Long) = "submission|$id"

    /** Inserts, then seals with the generated id in the same transaction. */
    fun create(chatId: Long, userId: Long, formVersion: Int?, profile: Profile, answers: Map<String, String>?, status: Status, at: Instant): Long =
        transaction(db) {
            val id = Submissions.insert {
                it[Submissions.chatId] = chatId; it[Submissions.userId] = userId; it[Submissions.formVersion] = formVersion
                it[Submissions.profile] = ByteArray(0); it[Submissions.status] = status.name; it[createdAt] = at
            }[Submissions.id]
            Submissions.update({ Submissions.id eq id }) {
                it[Submissions.profile] = crypto.seal(json.encodeToString(profile), aad(id))
                it[Submissions.answers] = answers?.let { a -> crypto.seal(json.encodeToString(a), aad(id)) }
            }
            id
        }

    fun get(id: Long): Submission? = transaction(db) {
        Submissions.selectAll().where { Submissions.id eq id }.singleOrNull()?.let(::submission)
    }

    fun list(chatId: Long, status: Status?): List<Submission> = transaction(db) {
        Submissions.selectAll().where {
            if (status == null) Submissions.chatId eq chatId
            else (Submissions.chatId eq chatId) and (Submissions.status eq status.name)
        }.orderBy(Submissions.id).map(::submission)
    }

    fun pendingFor(chatId: Long, userId: Long): Submission? = transaction(db) {
        Submissions.selectAll().where {
            (Submissions.chatId eq chatId) and (Submissions.userId eq userId) and (Submissions.status eq Status.PENDING.name)
        }.firstOrNull()?.let(::submission)
    }

    fun pendingInChats(chatIds: Collection<Long>): List<Submission> =
        if (chatIds.isEmpty()) emptyList() else transaction(db) {
            Submissions.selectAll().where { (Submissions.chatId inList chatIds) and (Submissions.status eq Status.PENDING.name) }
                .orderBy(Submissions.id).map(::submission)
        }

    /** First decision wins: only a PENDING row changes. */
    fun decide(id: Long, status: Status, by: Long, at: Instant): Boolean = transaction(db) {
        Submissions.update({ (Submissions.id eq id) and (Submissions.status eq Status.PENDING.name) }) {
            it[Submissions.status] = status.name; it[decidedBy] = by; it[decidedAt] = at
        } > 0
    }

    fun revert(id: Long) = transaction(db) {
        Submissions.update({ Submissions.id eq id }) {
            it[status] = Status.PENDING.name; it[decidedBy] = null; it[decidedAt] = null
        }
        Unit
    }

    /** Review messages go with it (FK cascade). */
    fun delete(id: Long) = transaction(db) { Submissions.deleteWhere { Submissions.id eq id }; Unit }

    fun deleteOlderThan(chatId: Long, cutoff: Instant): Int = transaction(db) {
        Submissions.deleteWhere { (Submissions.chatId eq chatId) and (Submissions.createdAt less cutoff) }
    }

    fun addReviewMessage(id: Long, adminId: Long, messageId: Long) = transaction(db) {
        ReviewMessages.upsert {
            it[submissionId] = id; it[ReviewMessages.adminId] = adminId; it[ReviewMessages.messageId] = messageId
        }
        Unit
    }

    /** (adminId, messageId) pairs. */
    fun reviewMessages(id: Long): List<Pair<Long, Long>> = transaction(db) {
        ReviewMessages.selectAll().where { ReviewMessages.submissionId eq id }
            .map { it[ReviewMessages.adminId] to it[ReviewMessages.messageId] }
    }

    private fun submission(r: ResultRow): Submission {
        val id = r[Submissions.id]
        return Submission(id, r[Submissions.chatId], r[Submissions.userId], r[Submissions.formVersion],
            json.decodeFromString(crypto.open(r[Submissions.profile], aad(id))),
            r[Submissions.answers]?.let { json.decodeFromString<Map<String, String>>(crypto.open(it, aad(id))) },
            Status.valueOf(r[Submissions.status]), r[Submissions.decidedBy], r[Submissions.decidedAt], r[Submissions.createdAt])
    }
}

class BotUserRepo(private val db: Database) {
    /** A known language is kept when [lang] is null. */
    fun started(userId: Long, lang: String?) = transaction(db) {
        BotUsers.upsert(onUpdateExclude = if (lang == null) listOf(BotUsers.lang) else null) {
            it[BotUsers.userId] = userId; it[dmOk] = true; it[BotUsers.lang] = lang
        }
        Unit
    }

    fun forbidden(userId: Long) = transaction(db) {
        BotUsers.upsert(onUpdateExclude = listOf(BotUsers.lang)) {
            it[BotUsers.userId] = userId; it[dmOk] = false; it[lang] = null
        }
        Unit
    }

    fun dmOk(userId: Long): Boolean = transaction(db) {
        BotUsers.selectAll().where { BotUsers.userId eq userId }.singleOrNull()?.get(BotUsers.dmOk) ?: false
    }

    fun lang(userId: Long): String? = transaction(db) {
        BotUsers.selectAll().where { BotUsers.userId eq userId }.singleOrNull()?.get(BotUsers.lang)
    }
}
