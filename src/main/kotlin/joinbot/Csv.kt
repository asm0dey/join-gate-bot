package joinbot

private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

/** RFC 4180 cell; a leading formula character gets a `'` prefix so spreadsheets don't evaluate it. */
private fun cell(raw: String): String {
    val s = if (raw.isNotEmpty() && raw[0] in FORMULA_START) "'$raw" else raw
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/** [forms] are (version, form) pairs, any order. Columns: fixed ones, then field ids (latest version's order, then older-only ids newest first). */
fun toCsv(forms: List<Pair<Int, Form>>, subs: List<Submission>): String {
    val newestFirst = forms.sortedByDescending { it.first }
    val ids = LinkedHashSet<String>()
    val labels = HashMap<String, String>()
    for ((_, form) in newestFirst) for (f in form.fields) { ids += f.id; labels.putIfAbsent(f.id, f.prompt) }
    val fixed = listOf("id", "user_id", "name", "username", "status", "created_at", "decided_at")
    return buildString {
        append((fixed + ids.map { labels.getValue(it) }).joinToString(",") { cell(it) }).append("\r\n")
        for (s in subs) {
            val row = listOf(s.id.toString(), s.userId.toString(), s.profile.name, s.profile.username ?: "", s.status.name,
                s.createdAt.toString(), s.decidedAt?.toString() ?: "") + ids.map { s.answers?.get(it) ?: "" }
            append(row.joinToString(",") { cell(it) }).append("\r\n")
        }
    }
}
