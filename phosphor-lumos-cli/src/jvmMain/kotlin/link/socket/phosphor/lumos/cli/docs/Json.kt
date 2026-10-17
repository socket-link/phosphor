package link.socket.phosphor.lumos.cli.docs

/**
 * [value] as pretty-printed JSON, two spaces an indent: maps in their iteration order, lists,
 * strings, numbers, booleans and null. Small enough that `catalog.json` needs no JSON library at
 * runtime; `kotlinx-serialization-json` is a test dependency of this module.
 */
internal fun toJson(value: Any?): String = StringBuilder().also { it.appendJson(value, indent = "") }.toString()

private fun StringBuilder.appendJson(
    value: Any?,
    indent: String,
) {
    when (value) {
        null -> append("null")
        is String -> appendJsonString(value)
        is Boolean, is Int, is Long, is Float -> append(value.toString())
        is Map<*, *> ->
            appendEntries(value.entries.toList(), indent, open = '{', close = '}') { (key, item) ->
                appendJsonString(key as String)
                append(": ")
                appendJson(item, "$indent  ")
            }
        is List<*> -> appendEntries(value, indent, open = '[', close = ']') { item -> appendJson(item, "$indent  ") }
        else -> error("no JSON form for ${value::class.simpleName}: $value")
    }
}

private fun <T> StringBuilder.appendEntries(
    entries: List<T>,
    indent: String,
    open: Char,
    close: Char,
    entry: StringBuilder.(T) -> Unit,
) {
    append(open)
    if (entries.isEmpty()) {
        append(close)
        return
    }
    entries.forEachIndexed { i, item ->
        append(if (i == 0) "\n" else ",\n")
        append(indent).append("  ")
        entry(item)
    }
    append('\n').append(indent).append(close)
}

private fun StringBuilder.appendJsonString(text: String) {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}
