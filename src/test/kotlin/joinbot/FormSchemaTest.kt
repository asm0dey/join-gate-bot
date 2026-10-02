package joinbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

private const val SPEC_EXAMPLE_JSON = """
{ "welcome": "Hi! A few questions before you join…",
  "fields": [
    { "id": "a1", "type": "radio",   "prompt": "Where do you live?", "options": ["Limassol","Nicosia"], "other": true, "required": true },
    { "id": "a2", "type": "radio",   "prompt": "Agree to the rules?", "options": ["Yes","No"] },
    { "id": "a3", "type": "multi",   "prompt": "Interests?", "options": ["Music"], "min": 1, "max": 3 },
    { "id": "a4", "type": "text",    "prompt": "About you", "maxLen": 300 },
    { "id": "a5", "type": "int",     "prompt": "Age?", "min": 18, "max": 120 },
    { "id": "a6", "type": "link",    "prompt": "LinkedIn?", "required": false },
    { "id": "a7", "type": "consent", "prompt": "Privacy terms… Do you agree?" } ] }
"""

class FormSchemaTest : StringSpec({
    val radio = Radio("a1", "Where?", listOf("Limassol", "Nicosia"), other = true)
    val multi = Multi("a3", "Interests?", listOf("x", "y", "z"), min = 1, max = 2)
    val text = Text("a4", "About", maxLen = 5)
    val num = IntField("a5", "Age?", min = 18, max = 120)
    val link = Link("a6", "LinkedIn?", required = false)
    val consent = Consent("c", "Agree?")
    "validate" { listOf(
        Pair(radio, Input.Picked(1) to Check.Ok("Nicosia")),
        Pair(radio, Input.Picked(2) to Check.Invalid(Reason.BAD_OPTION)),
        Pair(radio, Input.Typed("Paphos") to Check.Ok("Paphos")),            // other=true accepts text
        Pair(radio.copy(other = false), Input.Typed("Paphos") to Check.Invalid(Reason.WRONG_KIND)),
        Pair(multi, Input.PickedMany(setOf(0, 2)) to Check.Ok("x, z")),
        Pair(multi, Input.PickedMany(emptySet()) to Check.Invalid(Reason.TOO_FEW)),
        Pair(multi, Input.PickedMany(setOf(0, 1, 2)) to Check.Invalid(Reason.TOO_MANY)),
        Pair(multi, Input.PickedMany(setOf(3)) to Check.Invalid(Reason.BAD_OPTION)),
        Pair(text, Input.Typed("  abc  ") to Check.Ok("abc")),
        Pair(text, Input.Typed("abcdef") to Check.Invalid(Reason.TOO_LONG)),
        Pair(text, Input.Typed("   ") to Check.Invalid(Reason.REQUIRED)),
        Pair(text, Input.Skip to Check.Invalid(Reason.REQUIRED)),
        Pair(num, Input.Typed("42") to Check.Ok("42")),
        Pair(num, Input.Typed("17") to Check.Invalid(Reason.TOO_SMALL)),
        Pair(num, Input.Typed("121") to Check.Invalid(Reason.TOO_LARGE)),
        Pair(num, Input.Typed("99999999999999999999") to Check.Invalid(Reason.NOT_A_NUMBER)),
        Pair(num, Input.Typed("4.2") to Check.Invalid(Reason.NOT_A_NUMBER)),
        Pair(link, Input.Typed("https://x.io/p") to Check.Ok("https://x.io/p")),
        Pair(link, Input.Typed("x.io") to Check.Invalid(Reason.NOT_A_LINK)),
        Pair(link, Input.Typed("javascript:alert(1)") to Check.Invalid(Reason.NOT_A_LINK)),
        Pair(link, Input.Typed("ftp://x.io") to Check.Invalid(Reason.NOT_A_LINK)),
        Pair(link, Input.Skip to Check.Ok("")),
        Pair(link, Input.Typed("https://пример.рф/x") to Check.Ok("https://пример.рф/x")),
        Pair(link, Input.Typed("https://x.io/путь") to Check.Ok("https://x.io/путь")),
        Pair(link, Input.Typed("https:///nohost") to Check.Invalid(Reason.NOT_A_LINK)),
        Pair(consent, Input.Picked(0) to Check.Ok("✓")),
        Pair(consent, Input.Typed("yes") to Check.Invalid(Reason.WRONG_KIND)),
    ).forEach { (f, ir) -> validate(f, ir.first) shouldBe ir.second } }
    "multi min defaults to 1 when required" { validate(Multi("m", "?", listOf("a")), Input.PickedMany(emptySet())) shouldBe Check.Invalid(Reason.TOO_FEW) }
    "spec example parses and round-trips" { val f = FormJson.decodeFromString<Form>(SPEC_EXAMPLE_JSON)
        f.fields.map { it::class } shouldBe listOf(Radio::class, Radio::class, Multi::class, Text::class, IntField::class, Link::class, Consent::class)
        FormJson.decodeFromString<Form>(FormJson.encodeToString(f)) shouldBe f }
    "validateForm" { listOf(
        Pair(Form("hi", listOf(text, text)), "a4: duplicate id"),
        Pair(Form("hi", listOf(Radio("r", "?", emptyList()))), "r: needs options"),
        Pair(Form("hi", listOf(num.copy(min = 5, max = 1))), "a5: min > max"),
        Pair(Form("hi", listOf(text.copy(maxLen = 5000))), "a4: maxLen must be 1..4096"),
        Pair(Form("hi", listOf(text.copy(prompt = " "))), "a4: empty prompt"),
        Pair(Form("hi", List(51) { Text("t$it", "?") }), "form: more than 50 fields"),
        Pair(Form("h".repeat(2001), listOf(text)), "welcome: longer than 2000 chars"),
        Pair(Form(" \n", listOf(text)), "welcome: empty"),
        Pair(Form("hi", listOf(text.copy(prompt = "p".repeat(1001)))), "a4: prompt longer than 1000 chars"),
        Pair(Form("hi", listOf(radio.copy(options = List(21) { "o$it" }))), "a1: more than 20 options"),
        Pair(Form("hi", listOf(radio.copy(options = listOf("o".repeat(65))))), "a1: option label empty or longer than 64 chars"),
        Pair(Form("hi", listOf(text.copy(id = " "))), "form: empty field id"),
        Pair(Form("hi", listOf(multi.copy(min = 4, max = null))), "a3: min exceeds option count"),
        Pair(Form("hi", listOf(multi.copy(min = -1, max = null))), "a3: min and max must not be negative"),
        Pair(Form("hi", listOf(multi.copy(min = null, max = -1))), "a3: min and max must not be negative"),
        Pair(Form("hi", listOf(multi.copy(min = 0, max = 0))), "a3: max must be at least 1 when required"),
    ).forEach { (f, msg) -> validateForm(f) shouldContain msg } }
    "limits are accepted exactly at the boundary" {
        validateForm(Form("w".repeat(2000), List(50) { Text("t$it", "p".repeat(1000)) })) shouldBe emptyList()
        validateForm(Form("hi", listOf(Radio("r", "?", List(20) { "o".repeat(64) })))) shouldBe emptyList()
    }
    "valid spec example has no errors" { validateForm(FormJson.decodeFromString(SPEC_EXAMPLE_JSON)) shouldBe emptyList() }
})
