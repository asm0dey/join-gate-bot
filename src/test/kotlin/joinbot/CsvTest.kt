package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

private val at = Instant.parse("2026-01-01T00:00:00Z")
private fun sub(id: Long, answers: Map<String, String>, name: String = "Ann") =
    Submission(id, -1, 5, 1, Profile(name, null), answers, Status.PENDING, null, null, at, Kind.JOIN)

class CsvTest : StringSpec({
    "union of field ids labelled with latest prompt" {
        val v1 = Form("", listOf(Text("a", "A1"), Text("b", "B1")))
        val v2 = Form("", listOf(Text("b", "B2"), Text("c", "C2")))
        val csv = toCsv(listOf(1 to v1, 2 to v2), listOf(sub(1, mapOf("a" to "x", "b" to "y"))))
        val lines = csv.trim().split("\r\n")
        lines[0] shouldBe "id,user_id,name,username,status,created_at,decided_at,B2,C2,A1"
        lines[1] shouldBe "1,5,Ann,,PENDING,2026-01-01T00:00:00Z,,y,,x"
    }
    "formula injection neutralised and quotes escaped" {
        val f = Form("", listOf(Text("a", "A")))
        toCsv(listOf(1 to f), listOf(sub(1, mapOf("a" to "=1+1")))) shouldContain ",'=1+1"
        toCsv(listOf(1 to f), listOf(sub(1, mapOf("a" to "a\"b,c")))) shouldContain "\"a\"\"b,c\""
        toCsv(listOf(1 to f), listOf(sub(1, emptyMap(), name = "@x"))) shouldContain ",'@x,"
    }
})
