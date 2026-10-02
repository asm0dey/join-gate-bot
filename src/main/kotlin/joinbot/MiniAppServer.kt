package joinbot

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("joinbot.MiniAppServer")

/**
 * The path prefix the mini app is mounted under, derived from `MINIAPP_URL`'s path: empty for a bare
 * hostname, otherwise the path without a trailing slash. Never throws (unparseable -> "").
 */
internal fun miniAppPathPrefix(url: String): String {
    val path = runCatching { java.net.URI(url).path }.getOrNull() ?: ""
    return path.trimEnd('/')
}

/** An absolute `http(s)://` URL with a non-blank host. */
internal fun isValidMiniAppUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
}

fun Application.miniApp(deps: MiniAppDeps, webDir: File, pathPrefix: String = "") {
    install(ContentNegotiation) { json(FormJson) }

    // Only the exception class name is logged, never message, path or stack trace.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            logger.error("join-gate-bot: mini app error class=${cause.javaClass.simpleName}")
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    routing {
        if (pathPrefix.isNotEmpty()) get(pathPrefix) { call.respondRedirect("$pathPrefix/", permanent = false) }
        route("$pathPrefix/api") { api(deps) }
        if (webDir.isDirectory) staticFiles(pathPrefix.ifEmpty { "/" }, webDir)
        else logger.warn("join-gate-bot: web directory missing, serving /api only")
    }
}

/** Started from `Main` only when MINIAPP_URL is set and valid. */
fun startMiniApp(cfg: Config, deps: MiniAppDeps): EmbeddedServer<*, *> {
    val prefix = miniAppPathPrefix(cfg.miniAppUrl.orEmpty())
    return embeddedServer(CIO, port = cfg.miniAppPort) { miniApp(deps, File(cfg.webDir), prefix) }.start(wait = false)
}
