package joinbot

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.time.Clock
import kotlinx.serialization.Serializable

class MiniAppDeps(
    val verify: (String) -> Viewer?, val groups: GroupRepo, val forms: FormRepo, val subs: SubmissionRepo,
    val admins: AdminCheck, val tg: Tg, val users: BotUserRepo, val clock: Clock,
)

@Serializable data class GroupDto(val id: Long, val title: String, val hasForm: Boolean, val retentionDays: Int, val active: Boolean)
@Serializable data class FormDto(val version: Int, val schema: Form?)
@Serializable data class SaveFormBody(val schema: Form, val baseVersion: Int)
@Serializable data class SavedDto(val version: Int)
@Serializable data class ErrorsDto(val errors: List<String>)
@Serializable data class SubmissionRow(
    val id: Long, val userId: Long, val name: String, val username: String?, val status: Status,
    val createdAt: String, val decidedBy: Long?,
)
@Serializable data class AnswerDto(val fieldId: String, val prompt: String, val value: String)
@Serializable data class SubmissionDetail(val row: SubmissionRow, val answers: List<AnswerDto>?)
@Serializable data class SettingsBody(val retentionDays: Int)

private fun Submission.row() = SubmissionRow(id, userId, profile.name, profile.username, status, createdAt.toString(), decidedBy)

/** Group titles go into a file name: keep a safe alphabet only. */
internal fun exportFileName(title: String) = title.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-") it else '_' }
    .joinToString("").take(64) + "-submissions.csv"

private suspend fun RoutingContext.viewer(d: MiniAppDeps): Viewer? {
    val v = call.request.headers["Authorization"]?.takeIf { it.startsWith("tma ") }?.removePrefix("tma ")?.let(d.verify)
    if (v == null) call.respond(HttpStatusCode.Unauthorized)
    return v
}

/**
 * Viewer, then group: bad id 400, unknown 404, not a decider 403. [fresh] for writes. An inactive group stays
 * reachable for its deciders, so its submissions can still be read, exported and deleted.
 */
private suspend fun RoutingContext.inGroup(d: MiniAppDeps, fresh: Boolean, block: suspend (Viewer, Group) -> Unit) {
    val v = viewer(d) ?: return
    val id = call.parameters["id"]?.toLongOrNull() ?: return call.respond(HttpStatusCode.BadRequest)
    val g = d.groups.get(id) ?: return call.respond(HttpStatusCode.NotFound)
    if (!d.admins.canDecide(id, v.userId, fresh)) return call.respond(HttpStatusCode.Forbidden)
    block(v, g)
}

private suspend inline fun <reified T : Any> RoutingContext.body(): T? =
    runCatching { call.receive<T>() }.getOrNull().also { if (it == null) call.respond(HttpStatusCode.BadRequest) }

fun Route.api(d: MiniAppDeps) {
    get("/groups") {
        val v = viewer(d) ?: return@get
        val out = d.groups.all().filter { d.admins.canDecide(it.chatId, v.userId) }
            .map { GroupDto(it.chatId, it.title, d.forms.current(it.chatId) != null, it.retentionDays, it.active) }
        call.respond(out)
    }
    get("/groups/{id}/form") {
        inGroup(d, false) { _, g -> call.respond(d.forms.current(g.chatId)?.let { FormDto(it.first, it.second) } ?: FormDto(0, null)) }
    }
    put("/groups/{id}/form") {
        inGroup(d, true) { v, g ->
            val b = body<SaveFormBody>() ?: return@inGroup
            val errors = validateForm(b.schema)
            if (errors.isNotEmpty()) return@inGroup call.respond(HttpStatusCode.BadRequest, ErrorsDto(errors))
            val saved = d.forms.save(g.chatId, b.schema, b.baseVersion, v.userId, d.clock.instant())
            if (saved != null) call.respond(SavedDto(saved))
            else call.respond(HttpStatusCode.Conflict, d.forms.current(g.chatId)?.let { FormDto(it.first, it.second) } ?: FormDto(0, null))
        }
    }
    get("/groups/{id}/submissions") {
        inGroup(d, false) { _, g ->
            val raw = call.request.queryParameters["status"]?.takeIf { it.isNotEmpty() }
            val status = raw?.let { s -> Status.entries.find { it.name == s } ?: return@inGroup call.respond(HttpStatusCode.BadRequest) }
            call.respond(d.subs.list(g.chatId, status).map { it.row() })
        }
    }
    get("/groups/{id}/submissions/{sid}") {
        inGroup(d, false) { _, g ->
            val s = submission(d, g) ?: return@inGroup
            val form = s.formVersion?.let { d.forms.version(g.chatId, it) }
            val answers = s.answers?.let { a -> form?.fields?.filter { it.id in a }?.map { AnswerDto(it.id, it.prompt, a.getValue(it.id)) }
                ?: a.map { (k, v) -> AnswerDto(k, k, v) } }
            call.respond(SubmissionDetail(s.row(), answers))
        }
    }
    delete("/groups/{id}/submissions/{sid}") {
        inGroup(d, true) { _, g ->
            val s = submission(d, g) ?: return@inGroup
            d.subs.delete(s.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/groups/{id}/export") {
        inGroup(d, true) { v, g ->
            val csv = toCsv(d.forms.all(g.chatId), d.subs.list(g.chatId, null)).toByteArray()
            when (d.tg.sendDocument(v.userId, exportFileName(g.title), csv)) {
                is Sent.Ok -> call.respond(HttpStatusCode.Accepted)
                Sent.Forbidden -> call.respond(HttpStatusCode.Conflict) // the user must start the bot first
                Sent.Failed -> call.respond(HttpStatusCode.BadGateway)
            }
        }
    }
    put("/groups/{id}/settings") {
        inGroup(d, true) { _, g ->
            val b = body<SettingsBody>() ?: return@inGroup
            if (b.retentionDays !in 1..3650) return@inGroup call.respond(HttpStatusCode.BadRequest)
            d.groups.setRetention(g.chatId, b.retentionDays)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The submission named by `{sid}` if it belongs to [g]; otherwise responds 400/404 and returns null. */
private suspend fun RoutingContext.submission(d: MiniAppDeps, g: Group): Submission? {
    val sid = call.parameters["sid"]?.toLongOrNull()
    if (sid == null) { call.respond(HttpStatusCode.BadRequest); return null }
    val s = d.subs.get(sid)?.takeIf { it.chatId == g.chatId }
    if (s == null) call.respond(HttpStatusCode.NotFound)
    return s
}
