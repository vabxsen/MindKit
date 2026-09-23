package com.localai.toolkit.ai.gemini

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiFailure
import kotlinx.coroutines.CancellationException

/**
 * Maps ML Kit GenAI error codes onto product-level failures.
 *
 * The codes below are the ones ML Kit actually defines in
 * `GenAiException.ErrorCode`. Anything unrecognised becomes
 * [AiFailure.Unknown] rather than being guessed at, so a new code in a future release
 * degrades to a generic message instead of a wrong one.
 */
internal fun Throwable.toAiFailure(): AiFailure {
    if (this is CancellationException) return AiFailure.Cancelled(technicalDetail())

    val exception = this as? GenAiException
        ?: findGenAiCause()
        ?: return AiFailure.Unknown(technicalDetail())

    val retryAfter = runCatching { exception.retryDelay.toMillis() }.getOrNull()

    return when (exception.errorCode) {
        GenAiException.ErrorCode.NOT_AVAILABLE,
        GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        -> AiFailure.Unsupported(exception.technicalDetail())

        GenAiException.ErrorCode.BUSY -> AiFailure.Busy(retryAfter, exception.technicalDetail())

        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
            AiFailure.QuotaExceeded(retryAfter, exception.technicalDetail())

        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
            AiFailure.BackgroundBlocked(exception.technicalDetail())

        GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
            AiFailure.InvalidInput(tooLarge = true, technicalDetail = exception.technicalDetail())

        GenAiException.ErrorCode.REQUEST_TOO_SMALL ->
            AiFailure.InvalidInput(tooLarge = false, technicalDetail = exception.technicalDetail())

        GenAiException.ErrorCode.INVALID_INPUT_IMAGE ->
            AiFailure.InvalidImage(exception.technicalDetail())

        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
            AiFailure.NotEnoughStorage(exception.technicalDetail())

        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE ->
            AiFailure.NeedsSystemUpdate(exception.technicalDetail())

        GenAiException.ErrorCode.CANCELLED -> AiFailure.Cancelled(exception.technicalDetail())

        GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
        GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
        GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR,
        GenAiException.ErrorCode.CACHE_PROCESSING_ERROR,
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR,
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR,
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR,
        GenAiException.ErrorCode.AUDIO_BUFFER_OVERFLOW,
        -> AiFailure.Unknown(exception.technicalDetail())

        else -> AiFailure.Unknown(exception.technicalDetail())
    }
}

/**
 * ML Kit wraps failures from its background executors, so the interesting exception is
 * often a cause rather than the throwable itself.
 */
private fun Throwable.findGenAiCause(): GenAiException? {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is GenAiException) return current
        current = current.cause
        depth++
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 5

private fun Throwable.technicalDetail(): String {
    val code = (this as? GenAiException)?.let { " code=${it.errorCode}" }.orEmpty()
    return "${this::class.simpleName}$code: ${message.orEmpty()}".trim()
}

/**
 * Converts a raw ML Kit feature status int into the app's capability status.
 *
 * [FeatureStatus] is an int annotation rather than an enum, so this is the one place
 * that has to know the numeric contract.
 */
internal fun Int.toCapabilityStatus(): AiCapabilityStatus = when (this) {
    FeatureStatus.AVAILABLE -> AiCapabilityStatus.AVAILABLE
    FeatureStatus.DOWNLOADABLE -> AiCapabilityStatus.DOWNLOADABLE
    FeatureStatus.DOWNLOADING -> AiCapabilityStatus.DOWNLOADING
    FeatureStatus.UNAVAILABLE -> AiCapabilityStatus.UNSUPPORTED
    else -> AiCapabilityStatus.UNKNOWN
}
