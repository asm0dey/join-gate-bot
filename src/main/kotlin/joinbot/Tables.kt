package joinbot

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.datetime.InstantColumnType
import java.time.Instant

/**
 * `Table.timestamp()` ships in the `exposed-kotlin-datetime` and `exposed-java-time`
 * modules, neither of which this project depends on (Global Constraints: exposed-core
 * + exposed-jdbc only). exposed-core does carry the abstract [InstantColumnType], which
 * works in terms of `kotlin.time.Instant` — a Kotlin-stdlib type, distinct from
 * `kotlinx.datetime.Instant` — so a two-line concrete subclass bridging it to
 * `java.time.Instant` gets a working timestamp column without adding a module.
 *
 * The stdlib also ships `kotlin.time.jdk8.toJavaInstant()`/`toKotlinInstant()` for
 * exactly this conversion, but that specific interop file does not resolve against
 * this project's Kotlin 2.4.20 toolchain (`error: unresolved reference` even in a
 * standalone `kotlinc` compile, independent of Gradle — verified by hand). The
 * seconds/nanos bridge below uses only stable, directly-confirmed-resolvable members
 * of both `Instant` types, and neither direction touches a time zone.
 *
 * The JVM-default-zone dependency in this column lives inside Exposed's
 * `InstantColumnType` itself, not here: writing a value formats it as SQL text via a
 * `LocalDateTime`, which `InstantColumnType` derives from the `Instant` using the
 * system default zone (`toLocalDateTime()`/equivalent internally), and reading
 * reverses that through the same zone. That is exactly what the hand-rolled JDBC this
 * replaced also did — `java.sql.Timestamp.from(instant)` / `Timestamp.toInstant()`
 * round-trip through the JVM's default time zone the same way — so this is not a
 * regression the port introduced.
 */
private class JavaInstantColumnType : InstantColumnType<Instant>() {
    override fun sqlType(): String = "TIMESTAMP WITH TIME ZONE"

    override fun toInstant(value: Instant): kotlin.time.Instant =
        kotlin.time.Instant.fromEpochSeconds(value.epochSecond, value.nano)

    override fun fromInstant(instant: kotlin.time.Instant): Instant =
        Instant.ofEpochSecond(instant.epochSeconds, instant.nanosecondsOfSecond.toLong())
}

private fun Table.timestamp(name: String): Column<Instant> = registerColumn(name, JavaInstantColumnType())

/** Mirror `V1__initial.sql` (foreign keys live only there; Flyway owns the schema); `DbTest` keeps the two from disagreeing silently. */
object GroupChats : Table("group_chat") {
    val chatId = long("chat_id")
    val title = text("title")
    val active = bool("active")
    val retentionDays = integer("retention_days").default(90)
    val nudgedAt = timestamp("nudged_at").nullable()
    override val primaryKey = PrimaryKey(chatId)
}

object Forms : Table("form") {
    val chatId = long("chat_id")
    val version = integer("version")
    val schemaJson = text("schema_json")
    val updatedBy = long("updated_by")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(chatId, version)
}

object FormSessions : Table("form_session") {
    val userId = long("user_id")
    val chatId = long("chat_id")
    val formVersion = integer("form_version")
    val step = integer("step")
    val answers = binary("answers")
    val lang = text("lang").nullable()
    val startedAt = timestamp("started_at")
    val touchedAt = timestamp("touched_at")
    override val primaryKey = PrimaryKey(userId, chatId)
}

object Submissions : Table("submission") {
    val id = long("id").autoIncrement()
    val chatId = long("chat_id")
    val userId = long("user_id")
    val formVersion = integer("form_version").nullable()
    val profile = binary("profile")
    val answers = binary("answers").nullable()
    val status = text("status")
    val decidedBy = long("decided_by").nullable()
    val decidedAt = timestamp("decided_at").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ReviewMessages : Table("review_message") {
    val submissionId = long("submission_id")
    val adminId = long("admin_id")
    val messageId = long("message_id")
    override val primaryKey = PrimaryKey(submissionId, adminId)
}

object BotUsers : Table("bot_user") {
    val userId = long("user_id")
    val dmOk = bool("dm_ok")
    val lang = text("lang").nullable()
    override val primaryKey = PrimaryKey(userId)
}

object Canary : Table("canary") {
    val id = integer("id")
    val sealed = binary("sealed")
    override val primaryKey = PrimaryKey(id)
}
