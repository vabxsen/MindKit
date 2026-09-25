package com.localai.toolkit.domain.usecase.devtools

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.util.Base64
import org.junit.Test

/**
 * The deterministic developer utilities.
 *
 * Each of these has exactly one correct answer, which is precisely why they are tested
 * rather than trusted: a wrong hash or a mis-decoded token is silently wrong, not
 * obviously broken.
 */
class DevToolsTest {

    // ---- Base64 ------------------------------------------------------------------

    @Test
    fun `base64 round trips utf8 text`() {
        val original = "Hello, world! Ünïcödé 😀"

        val encoded = DevTools.base64Encode(original)

        assertThat(DevTools.base64Decode(encoded)).isEqualTo(original)
    }

    @Test
    fun `base64 encoding matches the standard alphabet`() {
        assertThat(DevTools.base64Encode("hi")).isEqualTo("aGk=")
    }

    @Test
    fun `url-safe base64 avoids plus and slash`() {
        // Bytes that produce '+' and '/' in the standard alphabet.
        val input = String(byteArrayOf(-5, -17, -66), Charsets.ISO_8859_1)

        val urlSafe = DevTools.base64Encode(input, urlSafe = true)

        assertThat(urlSafe).doesNotContain("+")
        assertThat(urlSafe).doesNotContain("/")
    }

    @Test
    fun `base64 decoding accepts the url-safe alphabet`() {
        val urlSafe = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("subjects?_d=1".toByteArray())

        assertThat(DevTools.base64Decode(urlSafe)).isEqualTo("subjects?_d=1")
    }

    @Test
    fun `invalid base64 returns null rather than throwing`() {
        assertThat(DevTools.base64Decode("!!!not base64!!!")).isNull()
        assertThat(DevTools.base64Decode("")).isNull()
    }

    // ---- URL ---------------------------------------------------------------------

    @Test fun `base64 rejects garbage rather than silently ignoring punctuation`() {
        assertThat(DevTools.base64Decode("!!!")).isNull()
        assertThat(DevTools.base64Decode("aGk=!!!")).isNull()
        assertThat(DevTools.base64Decode("/w==")).isNull() // not UTF-8 text
        assertThat(DevTools.base64Decode("aG\nk=")).isEqualTo("hi")
        assertThat(DevTools.base64Decode("aGk")).isEqualTo("hi")
    }

    @Test fun `extreme dates never overflow or crash conversion`() {
        assertThat(DevTools.parseEpoch(Long.MIN_VALUE.toString())?.epochMillis).isEqualTo(Long.MIN_VALUE)
        assertThat(DevTools.parseIso8601("+999999999-12-31T23:59:59Z")).isNull()
    }

    @Test fun `jwt with out of range expiry still exposes the raw payload without crashing`() {
        val decoded = DevTools.decodeJwt(jwt("{}", """{"exp":9223372036854775807}"""))!!
        assertThat(decoded.expiresAt).isNull()
        assertThat(decoded.payload).contains("9223372036854775807")
    }

    @Test fun `jwt with extra segments is not silently truncated`() {
        assertThat(DevTools.decodeJwt(jwt("{}", "{}") + ".extra")).isNull()
    }

    @Test
    fun `url encoding uses percent-encoded spaces rather than plus`() {
        // URLEncoder is form encoding; a developer inspecting a URL expects %20.
        assertThat(DevTools.urlEncode("a b")).isEqualTo("a%20b")
    }

    @Test
    fun `url encoding escapes reserved characters`() {
        val encoded = DevTools.urlEncode("key=value&other=1")

        assertThat(encoded).contains("%3D")
        assertThat(encoded).contains("%26")
    }

    @Test
    fun `url decoding reverses encoding`() {
        val original = "path/to/thing?q=a b&x=1"

        assertThat(DevTools.urlDecode(DevTools.urlEncode(original))).isEqualTo(original)
    }

    @Test
    fun `a malformed escape returns null`() {
        assertThat(DevTools.urlDecode("%ZZ")).isNull()
    }

    // ---- Hashing -----------------------------------------------------------------

    @Test
    fun `hashes match published digests for the empty string`() {
        assertThat(DevTools.hash("", DevTools.HashAlgorithm.MD5))
            .isEqualTo("d41d8cd98f00b204e9800998ecf8427e")
        assertThat(DevTools.hash("", DevTools.HashAlgorithm.SHA1))
            .isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709")
        assertThat(DevTools.hash("", DevTools.HashAlgorithm.SHA256))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `sha256 matches the published digest for abc`() {
        assertThat(DevTools.hash("abc", DevTools.HashAlgorithm.SHA256))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `digests are lowercase hex of the expected length`() {
        DevTools.HashAlgorithm.entries.forEach { algorithm ->
            val digest = DevTools.hash("some input", algorithm)
            assertThat(digest).matches("[0-9a-f]+")
        }
        assertThat(DevTools.hash("x", DevTools.HashAlgorithm.SHA512)).hasLength(128)
    }

    @Test
    fun `hashing is utf8 based so non-ascii input is stable`() {
        assertThat(DevTools.hash("é", DevTools.HashAlgorithm.SHA256))
            .isEqualTo(DevTools.hash("é", DevTools.HashAlgorithm.SHA256))
    }

    // ---- UUID --------------------------------------------------------------------

    @Test
    fun `generated uuids are version 4 and unique`() {
        val uuids = DevTools.generateUuids(25)

        assertThat(uuids).hasSize(25)
        assertThat(uuids.toSet()).hasSize(25)
        uuids.forEach { uuid ->
            assertThat(uuid).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            )
        }
    }

    @Test
    fun `the requested count is clamped to a sane range`() {
        assertThat(DevTools.generateUuids(0)).hasSize(1)
        assertThat(DevTools.generateUuids(-5)).hasSize(1)
        assertThat(DevTools.generateUuids(10_000)).hasSize(50)
    }

    // ---- Timestamps --------------------------------------------------------------

    @Test
    fun `an epoch in seconds is recognised as seconds`() {
        val result = DevTools.parseEpoch("1758326400", ZoneId.of("UTC"))!!

        assertThat(result.epochSeconds).isEqualTo(1758326400L)
        assertThat(result.epochMillis).isEqualTo(1758326400000L)
    }

    @Test
    fun `an epoch in milliseconds is recognised as milliseconds`() {
        val result = DevTools.parseEpoch("1758326400000", ZoneId.of("UTC"))!!

        assertThat(result.epochSeconds).isEqualTo(1758326400L)
        assertThat(result.epochMillis).isEqualTo(1758326400000L)
    }

    @Test
    fun `iso8601 input is parsed`() {
        val result = DevTools.parseIso8601("2026-09-20T00:00:00Z", ZoneId.of("UTC"))!!

        assertThat(result.iso8601Utc).isEqualTo("2026-09-20T00:00:00Z")
    }

    @Test
    fun `an iso8601 value with an offset is parsed`() {
        val result = DevTools.parseIso8601("2026-09-20T02:00:00+02:00", ZoneId.of("UTC"))!!

        assertThat(result.iso8601Utc).isEqualTo("2026-09-20T00:00:00Z")
    }

    @Test
    fun `non-numeric and malformed input returns null`() {
        assertThat(DevTools.parseEpoch("not a number")).isNull()
        assertThat(DevTools.parseIso8601("2026-13-45")).isNull()
        assertThat(DevTools.parseIso8601("")).isNull()
    }

    @Test
    fun `a negative epoch before 1970 is supported`() {
        val result = DevTools.parseEpoch("-86400", ZoneId.of("UTC"))!!

        assertThat(result.iso8601Utc).isEqualTo("1969-12-31T00:00:00Z")
    }

    // ---- JWT ---------------------------------------------------------------------

    @Test
    fun `a jwt header and payload are decoded`() {
        val token = jwt(
            header = """{"alg":"HS256","typ":"JWT"}""",
            payload = """{"sub":"1234567890","name":"Ada"}""",
        )

        val decoded = DevTools.decodeJwt(token)!!

        assertThat(decoded.header).contains("HS256")
        assertThat(decoded.payload).contains("Ada")
        assertThat(decoded.signature).isEqualTo("sig")
    }

    @Test
    fun `an expiry claim is extracted and compared to now`() {
        val past = jwt(
            header = """{"alg":"none"}""",
            payload = """{"exp":1000000000}""",
        )

        val decoded = DevTools.decodeJwt(past)!!

        assertThat(decoded.expiresAt).isNotNull()
        assertThat(decoded.isExpired).isTrue()
    }

    @Test
    fun `a token with no expiry reports no expiry rather than guessing`() {
        val decoded = DevTools.decodeJwt(jwt("""{"alg":"none"}""", """{"sub":"x"}"""))!!

        assertThat(decoded.expiresAt).isNull()
        assertThat(decoded.isExpired).isNull()
    }

    @Test
    fun `the text exp inside another value is not mistaken for the claim`() {
        // A regex-based extractor would match this; the real parser does not.
        val decoded = DevTools.decodeJwt(jwt("""{"alg":"none"}""", """{"note":"exp:1234"}"""))!!

        assertThat(decoded.expiresAt).isNull()
    }

    @Test
    fun `a token with too few segments is rejected`() {
        assertThat(DevTools.decodeJwt("onlyonepart")).isNull()
    }

    @Test
    fun `a token with an undecodable segment is rejected`() {
        assertThat(DevTools.decodeJwt("!!!.!!!.sig")).isNull()
    }

    @Test
    fun `a two-segment token with no signature still decodes`() {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"x"}""".toByteArray())

        val decoded = DevTools.decodeJwt("$header.$payload")!!

        assertThat(decoded.signature).isEmpty()
    }

    @Test
    fun `a json segment is pretty-printed and a non-json one is left alone`() {
        assertThat(DevTools.prettyPrintIfJson("""{"a":1}""")).isEqualTo("{\n  \"a\": 1\n}")
        assertThat(DevTools.prettyPrintIfJson("not json")).isEqualTo("not json")
    }

    /** Builds a JWT with unpadded base64url segments, the way real tokens are encoded. */
    private fun jwt(header: String, payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            encoder.encodeToString(header.toByteArray()),
            encoder.encodeToString(payload.toByteArray()),
            encoder.encodeToString("sig".toByteArray()).let { "sig" },
        ).joinToString(".")
    }
}
