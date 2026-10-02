package joinbot

import eu.vendeli.tgbot.TelegramBot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf

/** One outgoing Telegram call: the method name, and the JSON body that was sent with it. */
data class Call(val path: String, val body: String)

private const val MESSAGE_RESULT =
    """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-100,"type":"group"}}}"""
private const val TRUE_RESULT = """{"ok":true,"result":true}"""

/** A bot that records every call and answers all of them as successes. */
fun recordingBot(sink: MutableList<Call>): TelegramBot {
    val client = HttpClient(MockEngine { request ->
        val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val path = request.url.encodedPath.substringAfterLast('/')
        sink += Call(path, bytes.decodeToString())
        respond(
            content = if (path.startsWith("send") || path.startsWith("edit")) MESSAGE_RESULT else TRUE_RESULT,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })
    return TelegramBot(token = "000:fake-token-for-tests", httpClient = client)
}

/** A bot whose every call fails with the given Telegram error. */
fun failingBot(code: Int, description: String): TelegramBot {
    val client = HttpClient(MockEngine {
        respond(
            content = """{"ok":false,"error_code":$code,"description":"$description"}""",
            status = HttpStatusCode.fromValue(code),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })
    return TelegramBot(token = "000:fake-token-for-tests", httpClient = client)
}
