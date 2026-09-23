package com.localai.toolkit.domain.usecase.devtools

/**
 * A small, strict JSON parser and pretty-printer.
 *
 * Hand-written rather than delegating to `org.json` for three reasons that matter here:
 *
 *  - `org.json` is lenient. It accepts unquoted keys and trailing commas, which makes it
 *    useless as a *validator* - the tool would call malformed JSON valid.
 *  - It reports failures as a bare message with no position, whereas a developer tool
 *    should say which line and column is wrong.
 *  - It is an Android framework class, so the logic could only be tested under
 *    Robolectric. This version is plain Kotlin and is tested directly on the JVM.
 *
 * Key order is preserved, because reordering a developer's JSON while "formatting" it
 * would be surprising.
 */
sealed interface JsonValue {
    data class JsonObject(val entries: List<Pair<String, JsonValue>>) : JsonValue
    data class JsonArray(val items: List<JsonValue>) : JsonValue
    data class JsonString(val value: String) : JsonValue

    /** Numbers are kept as their original text so precision is never lost in a round trip. */
    data class JsonNumber(val literal: String) : JsonValue
    data class JsonBoolean(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
}

/** Where parsing failed, in terms a developer can act on. */
data class JsonError(val message: String, val line: Int, val column: Int) {
    /** e.g. "Line 3, column 12: expected ':'". */
    override fun toString(): String = "Line $line, column $column: $message"
}

class JsonParseException(val error: JsonError) : Exception(error.toString())

/**
 * Parses [input] into a [JsonValue].
 *
 * @throws JsonParseException with a positioned [JsonError] when the text is not valid JSON.
 */
fun parseJson(input: String): JsonValue {
    val parser = JsonParser(input)
    val value = parser.parseValue()
    parser.skipWhitespace()
    if (!parser.atEnd()) parser.fail("unexpected trailing content")
    return value
}

/** Formats [value] with [indent] spaces per level. */
fun formatJson(value: JsonValue, indent: Int = 2): String =
    StringBuilder().also { writeJson(value, it, indent, depth = 0) }.toString()

/** Re-serialises [value] on a single line. */
fun minifyJson(value: JsonValue): String =
    StringBuilder().also { writeJson(value, it, indent = 0, depth = 0) }.toString()

private fun writeJson(value: JsonValue, out: StringBuilder, indent: Int, depth: Int) {
    val pretty = indent > 0
    val pad = if (pretty) " ".repeat(indent * (depth + 1)) else ""
    val closePad = if (pretty) " ".repeat(indent * depth) else ""
    val newline = if (pretty) "\n" else ""

    when (value) {
        is JsonValue.JsonObject -> {
            if (value.entries.isEmpty()) {
                out.append("{}")
                return
            }
            out.append('{').append(newline)
            value.entries.forEachIndexed { index, (key, child) ->
                out.append(pad)
                writeString(key, out)
                out.append(':')
                if (pretty) out.append(' ')
                writeJson(child, out, indent, depth + 1)
                if (index != value.entries.lastIndex) out.append(',')
                out.append(newline)
            }
            out.append(closePad).append('}')
        }

        is JsonValue.JsonArray -> {
            if (value.items.isEmpty()) {
                out.append("[]")
                return
            }
            out.append('[').append(newline)
            value.items.forEachIndexed { index, child ->
                out.append(pad)
                writeJson(child, out, indent, depth + 1)
                if (index != value.items.lastIndex) out.append(',')
                out.append(newline)
            }
            out.append(closePad).append(']')
        }

        is JsonValue.JsonString -> writeString(value.value, out)
        is JsonValue.JsonNumber -> out.append(value.literal)
        is JsonValue.JsonBoolean -> out.append(if (value.value) "true" else "false")
        JsonValue.JsonNull -> out.append("null")
    }
}

private fun writeString(value: String, out: StringBuilder) {
    out.append('"')
    value.forEach { char ->
        when (char) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '' -> out.append("\\f")
            else ->
                // Control characters must be escaped; everything else can go through as
                // UTF-8 rather than being turned into \u sequences.
                if (char < ' ') {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
        }
    }
    out.append('"')
}

private class JsonParser(private val input: String) {
    private var index = 0

    fun atEnd(): Boolean = index >= input.length

    fun parseValue(): JsonValue {
        skipWhitespace()
        if (atEnd()) fail("unexpected end of input")
        return when (val char = input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JsonString(parseString())
            't' -> parseLiteral("true", JsonValue.JsonBoolean(true))
            'f' -> parseLiteral("false", JsonValue.JsonBoolean(false))
            'n' -> parseLiteral("null", JsonValue.JsonNull)
            else ->
                if (char == '-' || char.isDigit()) {
                    parseNumber()
                } else {
                    fail("unexpected character '$char'")
                }
        }
    }

    private fun parseObject(): JsonValue {
        expect('{')
        val entries = mutableListOf<Pair<String, JsonValue>>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.JsonObject(entries)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected a quoted key")
            val key = parseString()
            skipWhitespace()
            expect(':')
            entries += key to parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.JsonObject(entries)
                }
                else -> fail("expected ',' or '}'")
            }
            // A comma followed by '}' is a trailing comma, which is not valid JSON.
            skipWhitespace()
            if (peek() == '}') fail("trailing comma before '}'")
        }
    }

    private fun parseArray(): JsonValue {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.JsonArray(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.JsonArray(items)
                }
                else -> fail("expected ',' or ']'")
            }
            skipWhitespace()
            if (peek() == ']') fail("trailing comma before ']'")
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (atEnd()) fail("unterminated string")
            when (val char = input[index]) {
                '"' -> {
                    index++
                    return builder.toString()
                }

                '\\' -> {
                    index++
                    if (atEnd()) fail("unterminated escape sequence")
                    when (val escape = input[index]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 >= input.length) fail("incomplete unicode escape")
                            val hex = input.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16) ?: fail("invalid unicode escape")
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> fail("invalid escape '\\$escape'")
                    }
                    index++
                }

                else -> {
                    if (char < ' ') fail("unescaped control character in string")
                    builder.append(char)
                    index++
                }
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = index
        if (peek() == '-') index++
        // Leading zeroes are not valid JSON, so "01" is rejected rather than silently
        // accepted the way a lenient parser would.
        if (peek() == '0') {
            index++
        } else {
            if (peek()?.isDigit() != true) fail("expected a digit")
            while (peek()?.isDigit() == true) index++
        }
        if (peek() == '.') {
            index++
            if (peek()?.isDigit() != true) fail("expected a digit after '.'")
            while (peek()?.isDigit() == true) index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            if (peek()?.isDigit() != true) fail("expected a digit in the exponent")
            while (peek()?.isDigit() == true) index++
        }
        return JsonValue.JsonNumber(input.substring(start, index))
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!input.startsWith(literal, index)) fail("expected '$literal'")
        index += literal.length
        return value
    }

    fun skipWhitespace() {
        while (index < input.length && input[index].isJsonWhitespace()) index++
    }

    private fun peek(): Char? = input.getOrNull(index)

    private fun expect(char: Char) {
        if (peek() != char) fail("expected '$char'")
        index++
    }

    fun fail(message: String): Nothing {
        // Position is computed only when something has actually gone wrong, so the happy
        // path never pays for line tracking.
        var line = 1
        var column = 1
        for (i in 0 until minOf(index, input.length)) {
            if (input[i] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        throw JsonParseException(JsonError(message, line, column))
    }
}

private fun Char.isJsonWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r'
