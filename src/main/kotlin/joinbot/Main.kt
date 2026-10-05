package joinbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.getMe
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("joinbot.Main")

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
        httpClient {
            requestTimeoutMillis = 45_000L
            maxRequestRetry = 3
            retryDelay = 2_000L
            retryStrategy = retryOnTooManyRequests()
        }
        // Failures come back as Response.Failure; Telegram.kt classifies them.
        throwExOnActionsFailure = false
    }
    val admins = AdminCheck(bot, clock, cfg.botToken.substringBefore(':').toLong())
    val members = MemberRepo(db)
    val review = ReviewService(subs, forms, groups, users, members, admins, bot, clock)
    val flow = ApplicantFlow(groups, forms, sessions, subs, users, review, bot, clock)
    Registry.flow = flow
    Registry.review = review
    Registry.registry = GroupRegistry(groups, sessions, subs, users, review, flow, bot)
    Registry.roster = Roster(members)
    Registry.users = users
    Registry.bot = bot

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
    // Long-poll client: CIO's default 15 s request timeout is shorter than the 30 s poll.
    val http = HttpClient(CIO) { engine { requestTimeout = 45_000 } }
    suspend fun menuButton(url: String?) {
        val ok = try {
            setDefaultMenuButton(http, cfg.botToken, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) logger.info("join-gate-bot: menu button set") else logger.warn("join-gate-bot: menu button failed")
    }
    menuButton(miniAppUrl)

    if (miniAppUrl != null) {
        val deps = MiniAppDeps(InitDataVerifier(cfg.botToken)::verify, groups, forms, subs, admins, bot, users, clock)
        // A bind failure must not take polling down with it, nor leave a menu button pointing at nothing.
        try {
            startMiniApp(cfg, deps)
            logger.info("join-gate-bot: mini app listening")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("join-gate-bot: mini app failed to start: {}", e.javaClass.simpleName)
            menuButton(null)
        }
    }

    Purge(sessions, flow, subs, groups, clock).start(this)

    // getUpdates is refused while a webhook is set; best effort, polling's own errors cover the rest
    val unhooked = try {
        deleteWebhook(http, cfg.botToken)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("join-gate-bot: deleteWebhook failed: {}", e.javaClass.simpleName)
        null
    }
    if (unhooked == false) logger.warn("join-gate-bot: deleteWebhook was rejected")

    logger.info("join-gate-bot: listening")
    val polling = launch { poll(http, cfg.botToken) { bot.update.handle(it) } }
    // docker stop sends SIGTERM: let the batch in flight finish before the JVM goes
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { polling.cancelAndJoin() } })
    polling.join()
    if (!polling.isCancelled) exitProcess(1) // poll returns only when Telegram rejected the token
}

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

/** One raw Bot API call, like getUpdates; true when Telegram accepted it. [client] is caller-owned; never logs [token]. */
internal suspend fun deleteWebhook(client: HttpClient, token: String): Boolean =
    client.post("https://api.telegram.org/bot$token/deleteWebhook").status.isSuccess()

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
