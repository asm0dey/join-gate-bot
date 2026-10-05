package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val G1 = -100L
private const val G2 = -200L
private val form = Form("hi", listOf(Text("q1", "Why?"), Text("q2", "Where?")))

private class ApiEnv(name: String, val webDir: File = File("/nonexistent")) {
    val db = testDb(name)
    val clock = TestClock()
    val tg = FakeTelegram()
    val groups = GroupRepo(db); val forms = FormRepo(db); val subs = SubmissionRepo(db, testCrypto())
    val deps = MiniAppDeps({ t -> t.removePrefix("u").toLongOrNull()?.let { Viewer(it, null, null) } }, groups, forms, subs,
        AdminCheck(tg.bot, clock, 0), tg.bot, BotUserRepo(db), clock)

    init {
        groups.upsert(G1, "My Club/ü", true); groups.upsert(G2, "Other", true)
        tg.adminsOf[G1] = listOf(Admin(1, "A", false, true))
        tg.adminsOf[G2] = listOf(Admin(2, "B", false, true))
    }

    fun run(block: suspend ApiClient.() -> Unit) = testApplication {
        application { miniApp(deps, webDir) }
        ApiClient(this).block()
    }
}

private class ApiClient(val app: ApplicationTestBuilder) {
    suspend fun get(path: String, user: Long = 1) = app.client.get(path) { header(HttpHeaders.Authorization, "tma u$user") }
    suspend fun delete(path: String, user: Long = 1) = app.client.delete(path) { header(HttpHeaders.Authorization, "tma u$user") }
    suspend fun post(path: String, user: Long = 1) = app.client.post(path) { header(HttpHeaders.Authorization, "tma u$user") }
    suspend fun put(path: String, body: String, user: Long = 1): HttpResponse = app.client.put(path) {
        header(HttpHeaders.Authorization, "tma u$user"); header(HttpHeaders.ContentType, ContentType.Application.Json.toString()); setBody(body)
    }
}

private fun saveBody(f: Form, base: Int) = """{"schema":${FormJson.encodeToString(f)},"baseVersion":$base}"""
private fun parse(s: String) = Json.parseToJsonElement(s)

class MiniAppApiTest : StringSpec({
    "bad initData or no header → 401" {
        ApiEnv("api-401").run {
            app.client.get("/api/groups") { header(HttpHeaders.Authorization, "tma junk") }.status shouldBe HttpStatusCode.Unauthorized
            app.client.get("/api/groups").status shouldBe HttpStatusCode.Unauthorized
        }
    }
    "non-admin → 403, unknown → 404, bad id → 400" {
        ApiEnv("api-403").also { it.groups.upsert(-300, "Gone", false) }.run {
            get("/api/groups/$G1/form", user = 9).status shouldBe HttpStatusCode.Forbidden
            get("/api/groups/-999/form").status shouldBe HttpStatusCode.NotFound
            get("/api/groups/-300/form").status shouldBe HttpStatusCode.Forbidden // inactive, and nobody decides there
            get("/api/groups/abc/form").status shouldBe HttpStatusCode.BadRequest
        }
    }
    "an inactive group stays listed and usable for its deciders" {
        val e = ApiEnv("api-inactive")
        e.groups.upsert(-300, "Gone", false)
        e.tg.adminsOf[-300] = listOf(Admin(1, "A", false, true))
        val id = e.subs.create(-300, 5, null, Profile("Ann", null), null, Status.PENDING, e.clock.instant())
        e.run {
            val listed = parse(get("/api/groups").bodyAsText()).jsonArray.associate {
                it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["active"]!!.jsonPrimitive.content
            }
            listed shouldBe mapOf("-100" to "true", "-300" to "false")
            get("/api/groups/-300/form").status shouldBe HttpStatusCode.OK
            put("/api/groups/-300/form", saveBody(form, 0)).status shouldBe HttpStatusCode.OK
            get("/api/groups/-300/submissions/$id").status shouldBe HttpStatusCode.OK
            post("/api/groups/-300/export").status shouldBe HttpStatusCode.Accepted
            put("/api/groups/-300/settings", """{"retentionDays":30}""").status shouldBe HttpStatusCode.NoContent
            delete("/api/groups/-300/submissions/$id").status shouldBe HttpStatusCode.NoContent
            get("/api/groups/-300/form", user = 2).status shouldBe HttpStatusCode.Forbidden
        }
    }
    "groups lists only where viewer decides" {
        ApiEnv("api-groups").run {
            val r = parse(get("/api/groups").bodyAsText()).jsonArray
            r.size shouldBe 1
            r[0].jsonObject["id"]!!.jsonPrimitive.content shouldBe "-100"
            r[0].jsonObject["hasForm"]!!.jsonPrimitive.content shouldBe "false"
        }
    }
    "save then stale save → 409 with current version" {
        ApiEnv("api-save").run {
            get("/api/groups/$G1/form").bodyAsText() shouldBe """{"version":0,"schema":null}"""
            val ok = put("/api/groups/$G1/form", saveBody(form, 0))
            ok.status shouldBe HttpStatusCode.OK
            ok.bodyAsText() shouldBe """{"version":1}"""
            val stale = put("/api/groups/$G1/form", saveBody(form, 0))
            stale.status shouldBe HttpStatusCode.Conflict
            parse(stale.bodyAsText()).jsonObject["version"]!!.jsonPrimitive.int shouldBe 1
            parse(get("/api/groups").bodyAsText()).jsonArray[0].jsonObject["hasForm"]!!.jsonPrimitive.content shouldBe "true"
        }
    }
    "writes check admin rights afresh, past the cache" {
        val e = ApiEnv("api-fresh")
        e.run {
            get("/api/groups/$G1/form").status shouldBe HttpStatusCode.OK // caches user 1 as a decider
            e.tg.adminsOf[G1] = emptyList()
            get("/api/groups/$G1/form").status shouldBe HttpStatusCode.OK // reads may use the cache
            put("/api/groups/$G1/form", saveBody(form, 0)).status shouldBe HttpStatusCode.Forbidden
        }
    }
    "invalid schema → 400 with errors" {
        ApiEnv("api-invalid").run {
            val dup = Form("", listOf(Text("a4", "x"), Text("a4", "y")))
            val r = put("/api/groups/$G1/form", saveBody(dup, 0))
            r.status shouldBe HttpStatusCode.BadRequest
            r.bodyAsText() shouldContain "a4: duplicate id"
        }
    }
    "malformed body → 400" {
        ApiEnv("api-malformed").run { put("/api/groups/$G1/form", "{nope").status shouldBe HttpStatusCode.BadRequest }
    }
    "submission detail labels by pinned form version" {
        val e = ApiEnv("api-detail")
        e.forms.save(G1, form, 0, 1, e.clock.instant())
        e.forms.save(G1, Form("", listOf(Text("q1", "Renamed"))), 1, 1, e.clock.instant())
        val id = e.subs.create(G1, 5, 1, Profile("Ann", "ann"), mapOf("q1" to "fun", "q2" to "Oslo"), Status.PENDING, e.clock.instant())
        e.run {
            val d = parse(get("/api/groups/$G1/submissions/$id").bodyAsText()).jsonObject
            d["row"]!!.jsonObject["createdAt"]!!.jsonPrimitive.content shouldBe "2026-01-01T00:00:00Z"
            d["answers"]!!.jsonArray.map { it.jsonObject["prompt"]!!.jsonPrimitive.content } shouldBe listOf("Why?", "Where?")
            parse(get("/api/groups/$G1/submissions?status=PENDING").bodyAsText()).jsonArray.size shouldBe 1
            parse(get("/api/groups/$G1/submissions?status=APPROVED").bodyAsText()).jsonArray.size shouldBe 0
            get("/api/groups/$G1/submissions?status=bogus").status shouldBe HttpStatusCode.BadRequest
        }
    }
    "submission detail flags a partial submission" {
        val e = ApiEnv("api-partial")
        e.forms.save(G1, form, 0, 1, e.clock.instant())
        val partial = e.subs.create(G1, 5, 1, Profile("Ann", null), mapOf("q1" to "fun"), Status.PENDING, e.clock.instant())
        val none = e.subs.create(G1, 6, 1, Profile("Bob", null), null, Status.PENDING, e.clock.instant())
        val full = e.subs.create(G1, 7, 1, Profile("Cy", null), mapOf("q1" to "fun", "q2" to ""), Status.PENDING, e.clock.instant())
        e.run {
            suspend fun flag(id: Long) = parse(get("/api/groups/$G1/submissions/$id").bodyAsText()).jsonObject["partial"]!!.jsonPrimitive.content
            flag(partial) shouldBe "true"
            flag(none) shouldBe "true"
            flag(full) shouldBe "false"
        }
    }
    "submission of another group is 404" {
        val e = ApiEnv("api-cross")
        val id = e.subs.create(G2, 5, null, Profile("Ann", null), null, Status.PENDING, e.clock.instant())
        e.run { get("/api/groups/$G1/submissions/$id").status shouldBe HttpStatusCode.NotFound }
    }
    "delete removes it" {
        val e = ApiEnv("api-delete")
        val id = e.subs.create(G1, 5, null, Profile("Ann", null), null, Status.PENDING, e.clock.instant())
        e.run {
            delete("/api/groups/$G1/submissions/$id").status shouldBe HttpStatusCode.NoContent
            get("/api/groups/$G1/submissions/$id").status shouldBe HttpStatusCode.NotFound
        }
    }
    "export DMs a CSV with a sanitised name" {
        val e = ApiEnv("api-export")
        e.run {
            post("/api/groups/$G1/export").status shouldBe HttpStatusCode.Accepted
            e.tg.calls shouldBe listOf("doc 1 My_Club__-submissions.csv")
        }
        e.tg.sendResult = Sent.Forbidden
        e.run { post("/api/groups/$G1/export").status shouldBe HttpStatusCode.Conflict }
    }
    "settings bounds" {
        val e = ApiEnv("api-settings")
        e.run {
            put("/api/groups/$G1/settings", """{"retentionDays":0}""").status shouldBe HttpStatusCode.BadRequest
            put("/api/groups/$G1/settings", """{"retentionDays":3651}""").status shouldBe HttpStatusCode.BadRequest
            put("/api/groups/$G1/settings", """{"retentionDays":30}""").status shouldBe HttpStatusCode.NoContent
            parse(get("/api/groups").bodyAsText()).jsonArray[0].jsonObject["retentionDays"]!!.jsonPrimitive.int shouldBe 30
        }
    }
    "static index served, api not shadowed" {
        val dir = Files.createTempDirectory("web").toFile().also { File(it, "index.html").writeText("MARKER-INDEX") }
        ApiEnv("api-static", dir).run {
            app.client.get("/").bodyAsText() shouldContain "MARKER-INDEX"
            app.client.get("/api/groups").status shouldBe HttpStatusCode.Unauthorized
            get("/api/nope").status shouldBe HttpStatusCode.NotFound
        }
        dir.deleteRecursively()
    }
})
