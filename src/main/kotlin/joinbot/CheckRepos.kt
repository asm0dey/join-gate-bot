package joinbot

import java.time.Instant
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

data class Check(val chatId: Long, val deadline: Instant, val startedBy: Long, val startedAt: Instant, val closedAt: Instant?)

/** The roster: people the bot has seen in a chat, and when each last passed a form. */
class MemberRepo(private val db: Database) {
    /** Inserts when absent; never touches `passed_at`. */
    fun seen(chatId: Long, userId: Long) = transaction(db) {
        Members.insertIgnore { it[Members.chatId] = chatId; it[Members.userId] = userId }
        Unit
    }

    fun pass(chatId: Long, userId: Long, at: Instant) = transaction(db) {
        Members.upsert { it[Members.chatId] = chatId; it[Members.userId] = userId; it[passedAt] = at }
        Unit
    }

    /** True for the one caller that deleted the row. */
    fun remove(chatId: Long, userId: Long): Boolean = transaction(db) {
        Members.deleteWhere { (Members.chatId eq chatId) and (Members.userId eq userId) } > 0
    }

    fun passedAt(chatId: Long, userId: Long): Instant? = transaction(db) {
        Members.selectAll().where { (Members.chatId eq chatId) and (Members.userId eq userId) }.singleOrNull()?.get(Members.passedAt)
    }

    fun known(chatId: Long, userId: Long): Boolean = transaction(db) {
        !Members.selectAll().where { (Members.chatId eq chatId) and (Members.userId eq userId) }.empty()
    }

    fun count(chatId: Long): Int = transaction(db) { Members.selectAll().where { Members.chatId eq chatId }.count().toInt() }

    fun passedGroups(userId: Long): List<Long> = transaction(db) {
        Members.selectAll().where { (Members.userId eq userId) and Members.passedAt.isNotNull() }.map { it[Members.chatId] }
    }

    fun unpassed(chatId: Long): List<Long> = transaction(db) {
        Members.selectAll().where { (Members.chatId eq chatId) and Members.passedAt.isNull() }.map { it[Members.userId] }
    }
}

/** One Check per chat: a new `/remind` overwrites the row (see [open]). */
class CheckRepo(private val db: Database) {
    /** Starts or restarts the chat's check: upserts with `closed_at = null` and forgets the old messages and notices. */
    fun open(chatId: Long, deadline: Instant, by: Long, at: Instant) = transaction(db) {
        Rechecks.upsert {
            it[Rechecks.chatId] = chatId; it[Rechecks.deadline] = deadline; it[startedBy] = by
            it[startedAt] = at; it[closedAt] = null
        }
        RecheckMessages.deleteWhere { RecheckMessages.chatId eq chatId }
        RecheckNotices.deleteWhere { RecheckNotices.chatId eq chatId }
        Unit
    }

    fun get(chatId: Long): Check? = transaction(db) {
        Rechecks.selectAll().where { Rechecks.chatId eq chatId }.singleOrNull()?.let(::check)
    }

    fun openCheck(chatId: Long): Check? = get(chatId)?.takeIf { it.closedAt == null }

    fun setDeadline(chatId: Long, deadline: Instant) = transaction(db) {
        Rechecks.update({ Rechecks.chatId eq chatId }) { it[Rechecks.deadline] = deadline }
        Unit
    }

    fun addMessage(chatId: Long, messageId: Long) = transaction(db) {
        RecheckMessages.insertIgnore { it[RecheckMessages.chatId] = chatId; it[RecheckMessages.messageId] = messageId }
        Unit
    }

    fun messages(chatId: Long): List<Long> = transaction(db) {
        RecheckMessages.selectAll().where { RecheckMessages.chatId eq chatId }.orderBy(RecheckMessages.messageId, SortOrder.ASC)
            .map { it[RecheckMessages.messageId] }
    }

    fun due(now: Instant): List<Check> = transaction(db) {
        Rechecks.selectAll().where { Rechecks.closedAt.isNull() and (Rechecks.deadline lessEq now) }.map(::check)
    }

    /** True for the one caller that closes it: only a row with `closed_at IS NULL` changes. */
    fun close(chatId: Long, at: Instant): Boolean = transaction(db) {
        Rechecks.update({ (Rechecks.chatId eq chatId) and Rechecks.closedAt.isNull() }) { it[closedAt] = at } > 0
    }

    fun notice(chatId: Long, adminId: Long, delivered: Boolean) = transaction(db) {
        RecheckNotices.upsert {
            it[RecheckNotices.chatId] = chatId; it[RecheckNotices.adminId] = adminId; it[RecheckNotices.delivered] = delivered
        }
        Unit
    }

    /** Chat ids whose deadline list [adminId] has not received. */
    fun undelivered(adminId: Long): List<Long> = transaction(db) {
        RecheckNotices.selectAll().where { (RecheckNotices.adminId eq adminId) and (RecheckNotices.delivered eq false) }
            .map { it[RecheckNotices.chatId] }
    }

    private fun check(r: ResultRow) =
        Check(r[Rechecks.chatId], r[Rechecks.deadline], r[Rechecks.startedBy], r[Rechecks.startedAt], r[Rechecks.closedAt])
}
