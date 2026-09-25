package com.localai.toolkit.domain.usecase.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The JSON parser and formatter.
 *
 * A developer tool that calls malformed JSON valid is worse than no tool, so the
 * rejection cases get as much attention as the happy path. The lenient behaviours that
 * `org.json` would have allowed - trailing commas, unquoted keys, leading zeroes - are
 * each asserted to fail.
 */
class JsonTest {

    @Test fun `deep and oversized JSON fail with readable errors rather than exhausting resources`() {
        val nested = "[".repeat(129) + "0" + "]".repeat(129)
        val failure = runCatching { parseJson(nested) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(JsonParseException::class.java)
        assertThat(failure!!.message).contains("nesting exceeds")
        assertThat(runCatching { parseJson(" ".repeat(MAX_JSON_INPUT_CHARS + 1)) }.exceptionOrNull())
            .isInstanceOf(JsonParseException::class.java)
        val valid = "[".repeat(128) + "0" + "]".repeat(128)
        assertThat(minifyJson(parseJson(valid))).isEqualTo(valid)
    }

    @Test fun `pretty printing refuses explosive indentation output`() {
        val nested = "[".repeat(127) + "[" + List(10_000) { "0" }.joinToString(",") + "]".repeat(128)
        val failure = runCatching { formatJson(parseJson(nested)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(JsonParseException::class.java)
        assertThat(failure!!.message).contains("formatted result exceeds")
    }

    @Test fun `non ASCII numbers and signed unicode escapes are not valid JSON`() {
        listOf("١", "1.١", "1e١", "\"\\u+123\"", "\"\\u-123\"").forEach { input ->
            assertThat(runCatching { parseJson(input) }.exceptionOrNull()).isInstanceOf(JsonParseException::class.java)
        }
    }

    @Test
    fun `an object is pretty-printed with two-space indentation`() {
        val formatted = formatJson(parseJson("""{"a":1,"b":"two"}"""))

        assertThat(formatted).isEqualTo(
            """
            {
              "a": 1,
              "b": "two"
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `key order is preserved rather than sorted`() {
        // Reordering a developer's JSON while "formatting" it would be surprising.
        val formatted = formatJson(parseJson("""{"zebra":1,"apple":2,"mango":3}"""))

        assertThat(formatted.indexOf("zebra")).isLessThan(formatted.indexOf("apple"))
        assertThat(formatted.indexOf("apple")).isLessThan(formatted.indexOf("mango"))
    }

    @Test
    fun `nested structures are indented by depth`() {
        val formatted = formatJson(parseJson("""{"outer":{"inner":[1,2]}}"""))

        assertThat(formatted).isEqualTo(
            """
            {
              "outer": {
                "inner": [
                  1,
                  2
                ]
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `empty containers stay on one line`() {
        assertThat(formatJson(parseJson("""{"a":{},"b":[]}"""))).isEqualTo(
            """
            {
              "a": {},
              "b": []
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `number precision survives a round trip`() {
        // Parsing into a Double would silently destroy this value.
        val literal = "12345678901234567890.000000001"
        val formatted = formatJson(parseJson("""{"n":$literal}"""))

        assertThat(formatted).contains(literal)
    }

    @Test
    fun `escapes are decoded on parse and re-encoded on format`() {
        val formatted = formatJson(parseJson("""{"s":"line\nbreak \"quoted\" é"}"""))

        assertThat(formatted).contains("""\n""")
        assertThat(formatted).contains("""\"quoted\"""")
        // Non-ASCII is emitted as itself rather than as an escape sequence.
        assertThat(formatted).contains("é")
    }

    @Test
    fun `minify removes all formatting`() {
        val minified = minifyJson(parseJson("{\n  \"a\" : [ 1, 2 ]\n}"))

        assertThat(minified).isEqualTo("""{"a":[1,2]}""")
    }

    @Test
    fun `top-level scalars and arrays are valid documents`() {
        assertThat(minifyJson(parseJson("42"))).isEqualTo("42")
        assertThat(minifyJson(parseJson("\"hi\""))).isEqualTo("\"hi\"")
        assertThat(minifyJson(parseJson("true"))).isEqualTo("true")
        assertThat(minifyJson(parseJson("null"))).isEqualTo("null")
        assertThat(minifyJson(parseJson("[1,2]"))).isEqualTo("[1,2]")
    }

    @Test
    fun `a trailing comma is rejected`() {
        assertThat(parseFailure("""{"a":1,}""")).isNotNull()
        assertThat(parseFailure("""[1,2,]""")).isNotNull()
    }

    @Test
    fun `an unquoted key is rejected`() {
        assertThat(parseFailure("""{a:1}""")).isNotNull()
    }

    @Test
    fun `a single-quoted string is rejected`() {
        assertThat(parseFailure("""{'a':1}""")).isNotNull()
    }

    @Test
    fun `a leading zero is rejected`() {
        assertThat(parseFailure("""{"a":01}""")).isNotNull()
    }

    @Test
    fun `trailing content after a complete value is rejected`() {
        assertThat(parseFailure("""{"a":1} extra""")).isNotNull()
    }

    @Test
    fun `an unterminated string is rejected`() {
        assertThat(parseFailure("""{"a":"open}""")).isNotNull()
    }

    @Test
    fun `empty input is rejected`() {
        assertThat(parseFailure("")).isNotNull()
        assertThat(parseFailure("   ")).isNotNull()
    }

    @Test
    fun `the error reports the line and column of the problem`() {
        val error = parseFailure(
            """
            {
              "a": 1
              "b": 2
            }
            """.trimIndent(),
        )

        // The missing comma is detected at the start of line 3.
        assertThat(error!!.line).isEqualTo(3)
        assertThat(error.column).isAtLeast(1)
        assertThat(error.toString()).contains("Line 3")
    }

    @Test
    fun `whitespace between tokens is accepted`() {
        val parsed = parseJson("  {  \"a\"  :  [ 1 , 2 ]  }  ")

        assertThat(minifyJson(parsed)).isEqualTo("""{"a":[1,2]}""")
    }

    private fun parseFailure(input: String): JsonError? =
        runCatching { parseJson(input) }
            .exceptionOrNull()
            ?.let { (it as? JsonParseException)?.error }
}
