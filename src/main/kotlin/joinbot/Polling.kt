package joinbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.internal.KtGramInternal
import eu.vendeli.tgbot.types.common.Update
import eu.vendeli.tgbot.types.component.ChatReference
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.types.component.UserReference
import eu.vendeli.tgbot.utils.common.processUpdate
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("joinbot.Polling")

/**
 * Telegram sends chat_join_request and my_chat_member only when they are asked for by name,
 * so this list is load-bearing: drop one and that update kind stops arriving, silently.
 */
internal val ALLOWED_UPDATES = listOf(
    UpdateType.MESSAGE, UpdateType.CALLBACK_QUERY, UpdateType.CHAT_JOIN_REQUEST, UpdateType.MY_CHAT_MEMBER,
)

/** Mirrors vendeli 9.6's internal `serde`, which its own getUpdates decodes with. */
@OptIn(ExperimentalSerializationApi::class)
internal val TG_JSON = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

/**
 * Decodes one getUpdates batch element by element, so one update vendeli cannot decode (a null `from`,
 * a Bot API shape newer than vendeli) is skipped instead of failing the batch and pinning the offset.
 * Returns the next offset (past the highest update_id seen), or null when no element had one.
 */
@OptIn(KtGramInternal::class)
internal suspend fun processBatch(json: JsonArray, dispatch: suspend (ProcessedUpdate) -> Unit): Long? {
    var next: Long? = null
    for (el in json) {
        val id = runCatching { el.jsonObject["update_id"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: continue
        next = maxOf(next ?: Long.MIN_VALUE, id + 1)
        val update = try {
            TG_JSON.decodeFromJsonElement(Update.serializer(), el).processUpdate()
        } catch (e: Exception) {
            // Class name only: kotlinx messages embed the JSON, which carries names and text.
            log.warn("skipped undecodable update: {}", e.javaClass.simpleName)
            continue
        }
        dispatch(update)
    }
    return next
}

/** Whose order an update belongs to: its sender, else its chat; null when it has neither. */
internal fun orderKey(u: ProcessedUpdate): Long? =
    // vendeli's `user` getters can throw (`from!!`) on shapes it decoded anyway
    runCatching { (u as? UserReference)?.user?.id ?: (u as? ChatReference)?.chat?.id }.getOrNull()

/**
 * Launches [block] once the job launched before it for [key] has finished, so one user's updates are handled in
 * arrival order while different users still run concurrently; a null [key] runs unchained. [last] holds the newest
 * job per key and loses it when that job finishes. Call from one coroutine only (the poll loop).
 */
internal fun CoroutineScope.launchInOrder(last: ConcurrentHashMap<Long, Job>, key: Long?, block: suspend () -> Unit): Job {
    if (key == null) return launch { block() }
    val prev = last[key]
    // lazy, so the completion hook is registered before the job can finish
    val job = launch(start = CoroutineStart.LAZY) { prev?.join(); block() }
    last[key] = job
    job.invokeOnCompletion { last.remove(key, job) }
    job.start()
    return job
}

private val BACKOFF = 5.seconds

/**
 * Long polling with vendeli kept for dispatch only. Exits on 401/403 (token revoked); backs off on anything
 * else. Never logs the URL or a message: the URL carries the token. [client] needs a request timeout above 30 s.
 */
internal suspend fun poll(client: HttpClient, token: String, bot: TelegramBot): Unit = supervisorScope {
    val allowed = TG_JSON.encodeToString(ListSerializer(UpdateType.serializer()), ALLOWED_UPDATES)
    var offset = 0L
    val last = ConcurrentHashMap<Long, Job>()
    while (true) {
        val response: HttpResponse
        val result: JsonArray
        try {
            response = client.get("https://api.telegram.org/bot$token/getUpdates") {
                parameter("offset", offset)
                parameter("timeout", 30)
                parameter("allowed_updates", allowed)
            }
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                log.error("getUpdates rejected the token ({}); exiting", response.status.value)
                exitProcess(1)
            }
            if (!response.status.isSuccess()) {
                log.warn("getUpdates failed: HTTP {}", response.status.value)
                delay(BACKOFF)
                continue
            }
            result = TG_JSON.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonArray
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("getUpdates failed: {}", e.javaClass.simpleName)
            delay(BACKOFF)
            continue
        }
        processBatch(result) { update ->
            launchInOrder(last, orderKey(update)) {
                try {
                    bot.update.handle(update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("dispatch failed: {}", e.javaClass.simpleName)
                }
            }
        }?.let { offset = it }
    }
}
