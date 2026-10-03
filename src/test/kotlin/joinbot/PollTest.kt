package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val BATCH = """{"ok":true,"result":[{"update_id":10,"message":{"message_id":1,"date":1,""" +
    """"chat":{"id":5,"type":"private","first_name":"Ann"},"from":{"id":5,"is_bot":false,"first_name":"Ann"},"text":"hi"}}]}"""

/** getUpdates as (offset, timeout) pairs. The first long poll gets [BATCH], later ones hang; a timeout=0 call gets nothing. */
private class FakeUpdates(private val status: HttpStatusCode = HttpStatusCode.OK) {
    val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val client = HttpClient(MockEngine { req ->
        val call = req.url.parameters["offset"]!! to req.url.parameters["timeout"]!!
        calls += call
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            call.second == "0" -> respond("""{"ok":true,"result":[]}""", status, json)
            calls.size == 1 -> respond(BATCH, status, json)
            else -> awaitCancellation()
        }
    })

    suspend fun awaitCalls(n: Int) = withTimeout(5.seconds) { while (calls.size < n) delay(10.milliseconds) }
}

class PollTest : StringSpec({
    "the next getUpdates waits until the batch is handled" {
        val tg = FakeUpdates()
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            val polling = launch(Dispatchers.Default) { poll(tg.client, "t") { gate.await() } }
            tg.awaitCalls(1)
            delay(200.milliseconds)
            tg.calls shouldBe listOf("0" to "30")
            gate.complete(Unit)
            tg.awaitCalls(2)
            tg.calls[1] shouldBe ("11" to "30")
            polling.cancelAndJoin()
        }
    }

    "stopping mid-batch lets the update finish, then confirms it" {
        val tg = FakeUpdates()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val handled = CompletableDeferred<Unit>()
        coroutineScope {
            val polling = launch(Dispatchers.Default) {
                poll(tg.client, "t") { started.complete(Unit); gate.await(); handled.complete(Unit) }
            }
            started.await()
            polling.cancel()
            delay(200.milliseconds)
            polling.isCompleted shouldBe false
            gate.complete(Unit)
            polling.join()
        }
        handled.isCompleted shouldBe true
        tg.calls shouldBe listOf("0" to "30", "11" to "0")
    }

    "a rejected token ends polling instead of exiting the process" {
        val tg = FakeUpdates(HttpStatusCode.Unauthorized)
        withTimeout(5.seconds) { poll(tg.client, "t") { } }
        tg.calls shouldBe listOf("0" to "30")
    }
})
