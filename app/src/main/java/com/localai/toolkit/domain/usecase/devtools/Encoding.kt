package com.localai.toolkit.domain.usecase.devtools

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * The deterministic developer utilities.
 *
 * None of these involve a model. Formatting JSON or decoding Base64 has exactly one right
 * answer, and routing that through a language model would be slower, less reliable and
 * would make an offline tool depend on hardware support it does not need.
 *
 * Everything here is pure Kotlin on `java.*` APIs available from the app's minSdk, so it
 * is unit tested directly rather than through Robolectric.
 */
object DevTools {

    // ---- Base64 ------------------------------------------------------------------

    fun base64Encode(input: String, urlSafe: Boolean = false): String {
        val encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
        return encoder.encodeToString(input.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Decodes Base64, accepting both standard and URL-safe alphabets.
     *
     * @return the decoded text, or null when [input] is not valid Base64 or does not
     *   decode to UTF-8 text. Returning null rather than throwing keeps the calling
     *   screen simple: invalid input is a state, not an exception.
     */
    fun base64Decode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(trimmed) }
            .recoverCatching { Base64.getUrlDecoder().decode(trimmed) }
            // Some encoders drop the '=' padding; mime decoding tolerates that.
            .recoverCatching { Base64.getMimeDecoder().decode(trimmed) }
            .getOrNull() ?: return null
        return runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
    }

    // ---- URL ---------------------------------------------------------------------

    fun urlEncode(input: String): String =
        // URLEncoder is form encoding, which turns a space into '+'. Percent-encoding is
        // what a developer inspecting a URL expects, so that one substitution is undone.
        URLEncoder.encode(input, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun urlDecode(input: String): String? = runCatching {
        URLDecoder.decode(input, StandardCharsets.UTF_8.name())
    }.getOrNull()

    // ---- Hashing -----------------------------------------------------------------

    enum class HashAlgorithm(val displayName: String, val jcaName: String) {
        MD5("MD5", "MD5"),
        SHA1("SHA-1", "SHA-1"),
        SHA256("SHA-256", "SHA-256"),
        SHA512("SHA-512", "SHA-512"),
    }

    /** Lowercase hex digest of [input] as UTF-8. */
    fun hash(input: String, algorithm: HashAlgorithm): String {
        val digest = MessageDigest.getInstance(algorithm.jcaName)
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    // ---- UUID --------------------------------------------------------------------

    /**
     * Generates [count] random (version 4) UUIDs.
     *
     * [UUID.randomUUID] uses a cryptographically strong generator, which is what makes
     * these safe to use as identifiers rather than merely unique-looking.
     */
    fun generateUuids(count: Int = 1, uppercase: Boolean = false): List<String> =
        (1..count.coerceIn(1, MAX_UUIDS)).map {
            val uuid = UUID.randomUUID().toString()
            if (uppercase) uuid.uppercase(Locale.ROOT) else uuid
        }

    // ---- Timestamps --------------------------------------------------------------

    data class TimestampResult(
        val epochSeconds: Long,
        val epochMillis: Long,
        val iso8601Utc: String,
        val localTime: String,
    )

    /**
     * Interprets [input] as an epoch timestamp.
     *
     * Values are treated as seconds or milliseconds based on magnitude: anything with
     * more than [SECONDS_DIGIT_LIMIT] digits is milliseconds. That heuristic is what
     * every other timestamp tool uses, and it is correct for any date this century.
     */
    fun parseEpoch(input: String, zone: ZoneId = ZoneId.systemDefault()): TimestampResult? {
        val value = input.trim().toLongOrNull() ?: return null
        val millis = if (kotlin.math.abs(value) > SECONDS_DIGIT_LIMIT) value else value * 1_000
        val instant = runCatching { Instant.ofEpochMilli(millis) }.getOrNull() ?: return null
        return instant.toResult(zone)
    }

    /** Parses an ISO-8601 timestamp such as "2026-09-20T12:00:00Z". */
    fun parseIso8601(input: String, zone: ZoneId = ZoneId.systemDefault()): TimestampResult? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Instant.parse(trimmed).toResult(zone)
        } catch (e: DateTimeParseException) {
            // Fall back to an offset-bearing form such as "2026-09-20T12:00:00+02:00".
            runCatching { ZonedDateTime.parse(trimmed).toInstant().toResult(zone) }.getOrNull()
        }
    }

    fun now(zone: ZoneId = ZoneId.systemDefault()): TimestampResult = Instant.now().toResult(zone)

    private fun Instant.toResult(zone: ZoneId) = TimestampResult(
        epochSeconds = epochSecond,
        epochMillis = toEpochMilli(),
        iso8601Utc = DateTimeFormatter.ISO_INSTANT.format(this),
        localTime = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
            .format(atZone(zone)),
    )

    // ---- JWT ---------------------------------------------------------------------

    data class DecodedJwt(
        val header: String,
        val payload: String,
        val signature: String,
        /** Expiry read from the `exp` claim, when present. */
        val expiresAt: TimestampResult?,
        val isExpired: Boolean?,
    )

    /**
     * Decodes the header and payload of a JWT.
     *
     * Decoding only. The signature is shown but deliberately not verified: verifying it
     * would need the issuer's key, and a tool that displayed "valid" without one would be
     * actively misleading.
     */
    fun decodeJwt(token: String, zone: ZoneId = ZoneId.systemDefault()): DecodedJwt? {
        val parts = token.trim().split(".")
        if (parts.size < 2) return null

        val header = decodeJwtSegment(parts[0]) ?: return null
        val payload = decodeJwtSegment(parts[1]) ?: return null
        val signature = parts.getOrNull(2).orEmpty()

        val expiry = extractExpiry(payload)?.let { seconds ->
            Instant.ofEpochSecond(seconds).toResult(zone)
        }

        return DecodedJwt(
            header = header,
            payload = payload,
            signature = signature,
            expiresAt = expiry,
            isExpired = expiry?.let { it.epochMillis < System.currentTimeMillis() },
        )
    }

    /** Pretty-prints a decoded segment when it parses as JSON, otherwise returns it raw. */
    fun prettyPrintIfJson(text: String): String =
        runCatching { formatJson(parseJson(text)) }.getOrDefault(text)

    private fun decodeJwtSegment(segment: String): String? {
        // JWT uses base64url without padding.
        val bytes = runCatching { Base64.getUrlDecoder().decode(segment.padForBase64()) }
            .getOrNull() ?: return null
        return runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun String.padForBase64(): String = when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }

    /**
     * Reads the numeric `exp` claim from a decoded payload.
     *
     * Uses the app's own parser rather than a regular expression so a token containing
     * the text "exp" inside another value cannot be mistaken for the claim.
     */
    private fun extractExpiry(payload: String): Long? {
        val parsed = runCatching { parseJson(payload) }.getOrNull() ?: return null
        val obj = parsed as? JsonValue.JsonObject ?: return null
        val exp = obj.entries.firstOrNull { it.first == "exp" }?.second ?: return null
        return (exp as? JsonValue.JsonNumber)?.literal?.toDoubleOrNull()?.toLong()
    }

    private const val MAX_UUIDS = 50

    /** Above this, an epoch value must be milliseconds rather than seconds. */
    private const val SECONDS_DIGIT_LIMIT = 99_999_999_999L
}
