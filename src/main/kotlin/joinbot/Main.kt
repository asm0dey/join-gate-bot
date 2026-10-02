package joinbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.getMe
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.types.component.isSuccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.time.Clock
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("joinbot.Main")

/**
 * Telegram sends chat_join_request and my_chat_member only when they are asked for by name,
 * so this list is load-bearing: drop one and that update kind stops arriving, silently.
 */
internal val ALLOWED_UPDATES = listOf(
    UpdateType.MESSAGE, UpdateType.CALLBACK_QUERY, UpdateType.CHAT_JOIN_REQUEST, UpdateType.MY_CHAT_MEMBER,
)

suspend fun main(): Unit = coroutineScope {
    val cfg: Config
    val db: Database
    val crypto: Crypto
    try {
        cfg = loadConfig(System::getenv)
        crypto = Crypto(cfg.dataKeyset)
        val ds = createDataSource(cfg)
        try {
            migrate(ds)
            db = connectExposed(ds)
            verifyKeyset(db, crypto)
        } catch (e: Exception) {
            ds.close()
            throw e
        }
    } catch (e: Exception) {
        // Class name only: messages can carry config values or key material.
        logger.error("startup failed: {}", e.javaClass.simpleName)
        exitProcess(1)
    }

    val clock = Clock.systemUTC()
    val groups = GroupRepo(db)
    val forms = FormRepo(db)
    val sessions = SessionRepo(db, crypto)
    val subs = SubmissionRepo(db, crypto)
    val users = BotUserRepo(db)

    val bot = TelegramBot(cfg.botToken, "joinbot") {
        commandParsing { restrictSpacesInCommands = true }
        updatesListener { updatesPollingTimeout = 30 }
        httpClient {
            requestTimeoutMillis = 45_000L
            maxRequestRetry = 3
            retryDelay = 2_000L
            retryStrategy = retryOnTooManyRequests()
        }
        // Failures come back as Response.Failure; VendeliTg classifies them.
        throwExOnActionsFailure = false
    }
    val tg = VendeliTg(bot)
    val admins = AdminCheck(tg, clock)
    val review = ReviewService(subs, forms, groups, users, admins, tg, clock)
    val flow = ApplicantFlow(groups, forms, sessions, subs, users, review, tg, clock)
    Registry.flow = flow
    Registry.review = review
    Registry.registry = GroupRegistry(groups, subs, users, review, flow, tg)
    Registry.users = users
    Registry.tg = tg

    when (val v = validateBotToken(bot)) {
        TokenValidation.Rejected -> {
            logger.error("join-gate-bot: Telegram rejected the bot token; exiting")
            exitProcess(1)
        }
        is TokenValidation.Unknown -> logger.warn("join-gate-bot: could not reach Telegram to check the token (${v.causeClass}); proceeding")
        TokenValidation.Valid -> {}
    }

    // An invalid MINIAPP_URL is treated as unset: no server, and the menu button goes back to Telegram's default.
    val miniAppUrl = cfg.miniAppUrl?.takeIf {
        isValidMiniAppUrl(it) || run { logger.warn("join-gate-bot: MINIAPP_URL is not an absolute URL, mini app not started"); false }
    }
    val menuOk = HttpClient(CIO).use { runCatching { setDefaultMenuButton(it, cfg.botToken, miniAppUrl) }.getOrDefault(false) }
    if (menuOk) logger.info("join-gate-bot: menu button set") else logger.warn("join-gate-bot: menu button failed")

    if (miniAppUrl != null) {
        val deps = MiniAppDeps(InitDataVerifier(cfg.botToken)::verify, groups, forms, subs, admins, tg, users, clock)
        // A bind failure must not take polling down with it.
        runCatching { startMiniApp(cfg, deps) }
            .onSuccess { logger.info("join-gate-bot: mini app listening") }
            .onFailure { logger.error("join-gate-bot: mini app failed to start: {}", it.javaClass.simpleName) }
    }

    Purge(sessions, flow, subs, groups, clock).start(this)

    logger.info("join-gate-bot: listening")
    // vendeli ends polling on any "fatal" error (a request timeout, an undecodable update). Restart it, but give up
    // loudly after repeated failures with no healthy session in between. Never log e.message: request URLs carry the token.
    var failures = 0
    while (true) {
        val started = System.nanoTime()
        try {
            bot.handleUpdates(ALLOWED_UPDATES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (System.nanoTime() - started >= HEALTHY_SESSION.inWholeNanoseconds) failures = 0
            failures++
            val chain = generateSequence(e as Throwable) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName }
            logger.warn("join-gate-bot: listener error ($chain); failure $failures/$MAX_FAILURES")
            runCatching { bot.update.stopListener() }
            if (failures >= MAX_FAILURES) {
                logger.error("join-gate-bot: giving up after $failures consecutive listener failures")
                exitProcess(1)
            }
            delay(5.seconds)
        }
    }
}

private const val MAX_FAILURES = 5

/** Just above the 30 s long-poll: a session this long almost certainly completed a poll. */
private val HEALTHY_SESSION = 35.seconds

/** Three-way on purpose: "could not tell" must never be read as "rejected". */
internal sealed interface TokenValidation {
    data object Valid : TokenValidation
    data object Rejected : TokenValidation
    data class Unknown(val causeClass: String) : TokenValidation
}

/**
 * One getMe. A Telegram-side rejection comes back as Response.Failure (throwExOnActionsFailure is false);
 * a thrown exception means the request never reached Telegram, so it says nothing about the token.
 */
internal suspend fun validateBotToken(bot: TelegramBot): TokenValidation = try {
    if (getMe().sendReturning(bot).await().isSuccess()) TokenValidation.Valid else TokenValidation.Rejected
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    TokenValidation.Unknown(e.javaClass.simpleName)
}

@Serializable
private data class WebAppUrlBody(val url: String)

@Serializable
private data class WebAppMenuButtonBody(val type: String, val text: String, @SerialName("web_app") val webApp: WebAppUrlBody)

@Serializable
private data class SetWebAppMenuButtonBody(@SerialName("menu_button") val menuButton: WebAppMenuButtonBody)

private const val DEFAULT_MENU_BUTTON_BODY = """{"menu_button":{"type":"default"}}"""

/**
 * Sets the default menu button for every private chat: a "Forms" web app button for [url], or Telegram's
 * built-in button when [url] is null. A direct Bot API call because vendeli's typed setChatMenuButton always
 * writes chat_id, and the default button is reached only by omitting it. [client] is caller-owned.
 * Returns whether Telegram accepted it; never logs [token] or [url].
 */
internal suspend fun setDefaultMenuButton(client: HttpClient, token: String, url: String?): Boolean {
    val payload = if (url != null) {
        Json.encodeToString(SetWebAppMenuButtonBody(WebAppMenuButtonBody("web_app", "Forms", WebAppUrlBody(url))))
    } else {
        DEFAULT_MENU_BUTTON_BODY
    }
    return client.post("https://api.telegram.org/bot$token/setChatMenuButton") {
        contentType(ContentType.Application.Json)
        setBody(payload)
    }.status.isSuccess()
}
