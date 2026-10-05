package joinbot

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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Telegram sends chat_join_request, my_chat_member, chat_member and message_reaction only when they are asked for by name,
 * so this list is load-bearing: drop one and that update kind stops arriving, silently.
 */
internal val ALLOWED_UPDATES = listOf(
    UpdateType.MESSAGE, UpdateType.CALLBACK_QUERY, UpdateType.CHAT_JOIN_REQUEST, UpdateType.MY_CHAT_MEMBER,
    UpdateType.CHAT_MEMBER, UpdateType.MESSAGE_REACTION,
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

/** How long a stopping [poll] waits for its last batch; under compose.yaml's stop_grace_period. */
private val DRAIN = 25.seconds

/**
 * Long polling with vendeli kept for dispatch only. Returns on 401/403 (token revoked); backs off on anything
 * else. Never logs the URL or a message: the URL carries the token. [client] needs a request timeout above 30 s.
 *
 * A batch is confirmed to Telegram (by the next getUpdates' offset) only once all its updates are handled, so
 * at most one batch is in flight and a crash gets that batch redelivered. Cancelling this lets the batch in
 * flight finish, for up to [DRAIN], then confirms it.
 */
internal suspend fun poll(client: HttpClient, token: String, handle: suspend (ProcessedUpdate) -> Unit): Unit = coroutineScope {
    // not a child: cancelling polling must not cancel a half-handled update
    val dispatch = CoroutineScope(coroutineContext + SupervisorJob())
    val allowed = TG_JSON.encodeToString(ListSerializer(UpdateType.serializer()), ALLOWED_UPDATES)
    var offset = 0L
    var inFlight: Pair<List<Job>, Long?> = emptyList<Job>() to null
    val last = ConcurrentHashMap<Long, Job>()
    try {
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
                    log.error("getUpdates rejected the token ({})", response.status.value)
                    return@coroutineScope
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
            val jobs = mutableListOf<Job>()
            val next = processBatch(result) { update ->
                jobs += dispatch.launchInOrder(last, orderKey(update)) {
                    try {
                        handle(update)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("dispatch failed: {}", e.javaClass.simpleName)
                    }
                }
            }
            inFlight = jobs to next
            jobs.joinAll()
            inFlight = emptyList<Job>() to null
            next?.let { offset = it }
        }
    } finally {
        // also confirms a finished batch whose next getUpdates never reached Telegram
        val (jobs, next) = inFlight
        withContext(NonCancellable) {
            if (withTimeoutOrNull(DRAIN) { jobs.joinAll() } == null) log.warn("stopped with updates still in flight")
            else (next ?: offset).takeIf { it > 0 }?.let { withTimeoutOrNull(5.seconds) { confirm(client, token, it) } }
        }
    }
}

/** Marks every update below [offset] handled, so the next start doesn't get them again. Best effort. */
private suspend fun confirm(client: HttpClient, token: String, offset: Long) {
    try {
        client.get("https://api.telegram.org/bot$token/getUpdates") {
            parameter("offset", offset)
            parameter("timeout", 0)
            parameter("limit", 1)
        }
    } catch (e: Exception) {
        log.warn("confirming the last batch failed: {}", e.javaClass.simpleName)
    }
}
