package joinbot

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class Form(val welcome: String, val fields: List<Field>)

@Serializable sealed interface Field { val id: String; val prompt: String; val required: Boolean }

@Serializable @SerialName("radio")
data class Radio(override val id: String, override val prompt: String, val options: List<String>,
                 val other: Boolean = false, override val required: Boolean = true) : Field

@Serializable @SerialName("multi")
data class Multi(override val id: String, override val prompt: String, val options: List<String>,
                 val min: Int? = null, val max: Int? = null, override val required: Boolean = true) : Field

/**
 * The fewest picks Done accepts. An optional multiple choice is "at least 0": Done with nothing picked
 * skips it, whatever min was left from when it was required. A required one defaults to one.
 */
val Multi.minPicks get() = if (required) min ?: 1 else 0

/** The most picks a tick may reach: an unset or oversized max means every option. */
val Multi.maxPicks get() = minOf(max ?: options.size, options.size)

@Serializable @SerialName("text")
data class Text(override val id: String, override val prompt: String, val maxLen: Int = TEXT_MAX,
                override val required: Boolean = true) : Field

@Serializable @SerialName("int")
data class IntField(override val id: String, override val prompt: String, val min: Long? = null,
                    val max: Long? = null, override val required: Boolean = true) : Field

@Serializable @SerialName("link")
data class Link(override val id: String, override val prompt: String, override val required: Boolean = true) : Field

@Serializable @SerialName("consent")
data class Consent(override val id: String, override val prompt: String) : Field { override val required get() = true }

const val TEXT_MAX = 4096
const val MAX_FIELDS = 50
const val MAX_OPTIONS = 20
const val MAX_OPTION_LEN = 64
const val MAX_PROMPT = 1000
const val MAX_WELCOME = 2000

val FormJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = false; encodeDefaults = false }

sealed interface Input {
    data class Typed(val text: String) : Input
    data class Picked(val idx: Int) : Input
    data class PickedMany(val idxs: Set<Int>) : Input
    data object Skip : Input
}

enum class Reason { REQUIRED, TOO_LONG, NOT_A_NUMBER, TOO_SMALL, TOO_LARGE, NOT_A_LINK, TOO_FEW, TOO_MANY, BAD_OPTION, WRONG_KIND }

sealed interface Check {
    data class Ok(val value: String) : Check
    data class Invalid(val reason: Reason) : Check
}

private fun ok(v: String): Check = Check.Ok(v)
private fun bad(r: Reason): Check = Check.Invalid(r)

fun validate(field: Field, input: Input): Check {
    if (input is Input.Skip) return if (field.required) bad(Reason.REQUIRED) else ok("")
    return when (field) {
        is Radio -> when (input) {
            is Input.Picked -> field.options.getOrNull(input.idx)?.let(::ok) ?: bad(Reason.BAD_OPTION)
            is Input.Typed -> if (!field.other) bad(Reason.WRONG_KIND) else typed(field, input.text, TEXT_MAX) { ok(it) }
            else -> bad(Reason.WRONG_KIND)
        }
        is Multi -> when (input) {
            is Input.PickedMany -> {
                val sel = input.idxs.sorted()
                val min = field.minPicks
                when {
                    sel.any { it !in field.options.indices } -> bad(Reason.BAD_OPTION)
                    sel.size < min -> bad(Reason.TOO_FEW)
                    field.max != null && sel.size > field.max -> bad(Reason.TOO_MANY)
                    else -> ok(sel.joinToString(", ") { field.options[it] })
                }
            }
            else -> bad(Reason.WRONG_KIND)
        }
        is Text -> if (input is Input.Typed) typed(field, input.text, field.maxLen) { ok(it) } else bad(Reason.WRONG_KIND)
        is IntField -> if (input is Input.Typed) typed(field, input.text, Int.MAX_VALUE) { t ->
            val n = t.toLongOrNull()
            when {
                n == null -> bad(Reason.NOT_A_NUMBER)
                field.min != null && n < field.min -> bad(Reason.TOO_SMALL)
                field.max != null && n > field.max -> bad(Reason.TOO_LARGE)
                else -> ok(n.toString())
            }
        } else bad(Reason.WRONG_KIND)
        is Link -> if (input is Input.Typed) typed(field, input.text, TEXT_MAX) { if (isHttpUrl(it)) ok(it) else bad(Reason.NOT_A_LINK) }
            else bad(Reason.WRONG_KIND)
        is Consent -> if (input == Input.Picked(0)) ok("✓") else bad(Reason.WRONG_KIND)
    }
}

/** Trims, treats blank as missing, enforces max length, then hands the trimmed text to [next]. */
private fun typed(field: Field, raw: String, maxLen: Int, next: (String) -> Check): Check {
    val t = raw.trim()
    return when {
        t.isEmpty() -> if (field.required) bad(Reason.REQUIRED) else ok("")
        t.length > maxLen -> bad(Reason.TOO_LONG)
        else -> next(t)
    }
}

private fun isHttpUrl(s: String): Boolean = try {
    val u = URI(s)
    // host is null for internationalized names (https://пример.рф), so fall back to the authority minus userinfo and port
    val host = u.host ?: u.authority?.substringAfterLast('@')?.substringBefore(':')
    u.scheme?.lowercase() in setOf("http", "https") && !host.isNullOrEmpty() && IDN.toASCII(host).isNotEmpty()
} catch (_: URISyntaxException) { false } catch (_: IllegalArgumentException) { false }

/** Every validateForm message lives here; Task 12 mirrors these strings in TypeScript. */
object FormErrors {
    const val FORM = "form"
    const val WELCOME = "welcome"
    const val TOO_MANY_FIELDS = "more than $MAX_FIELDS fields"
    const val WELCOME_TOO_LONG = "longer than $MAX_WELCOME chars"
    const val WELCOME_EMPTY = "empty"
    const val EMPTY_ID = "empty field id"
    const val DUPLICATE_ID = "duplicate id"
    const val EMPTY_PROMPT = "empty prompt"
    const val PROMPT_TOO_LONG = "prompt longer than $MAX_PROMPT chars"
    const val NEEDS_OPTIONS = "needs options"
    const val TOO_MANY_OPTIONS = "more than $MAX_OPTIONS options"
    const val BAD_OPTION_LABEL = "option label empty or longer than $MAX_OPTION_LEN chars"
    const val MIN_GT_MAX = "min > max"
    const val MIN_GT_OPTIONS = "min exceeds option count"
    const val NEGATIVE_LIMIT = "min and max must not be negative"
    const val MAX_ZERO_REQUIRED = "max must be at least 1 when required"
    const val BAD_MAX_LEN = "maxLen must be 1..$TEXT_MAX"
}

fun validateForm(form: Form): List<String> = buildList {
    if (form.fields.size > MAX_FIELDS) add("${FormErrors.FORM}: ${FormErrors.TOO_MANY_FIELDS}")
    // the welcome is its own message, and Telegram rejects an empty one
    if (form.welcome.isBlank()) add("${FormErrors.WELCOME}: ${FormErrors.WELCOME_EMPTY}")
    else if (form.welcome.length > MAX_WELCOME) add("${FormErrors.WELCOME}: ${FormErrors.WELCOME_TOO_LONG}")
    val seen = HashSet<String>()
    for (f in form.fields) {
        if (f.id.isBlank()) { add("${FormErrors.FORM}: ${FormErrors.EMPTY_ID}"); continue }
        val id = f.id
        fun err(m: String) = add("$id: $m")
        if (!seen.add(id)) err(FormErrors.DUPLICATE_ID)
        if (f.prompt.isBlank()) err(FormErrors.EMPTY_PROMPT)
        else if (f.prompt.length > MAX_PROMPT) err(FormErrors.PROMPT_TOO_LONG)
        val options = when (f) { is Radio -> f.options; is Multi -> f.options; else -> null }
        if (options != null) {
            if (options.isEmpty()) err(FormErrors.NEEDS_OPTIONS)
            if (options.size > MAX_OPTIONS) err(FormErrors.TOO_MANY_OPTIONS)
            if (options.any { it.isBlank() || it.length > MAX_OPTION_LEN }) err(FormErrors.BAD_OPTION_LABEL)
        }
        when (f) {
            is Multi -> {
                if (f.min != null && f.max != null && f.min > f.max) err(FormErrors.MIN_GT_MAX)
                if (f.min != null && f.min > f.options.size) err(FormErrors.MIN_GT_OPTIONS)
                if ((f.min ?: 0) < 0 || (f.max ?: 0) < 0) err(FormErrors.NEGATIVE_LIMIT)
                if (f.required && f.max == 0) err(FormErrors.MAX_ZERO_REQUIRED)
            }
            is IntField -> if (f.min != null && f.max != null && f.min > f.max) err(FormErrors.MIN_GT_MAX)
            is Text -> if (f.maxLen !in 1..TEXT_MAX) err(FormErrors.BAD_MAX_LEN)
            else -> {}
        }
    }
}
