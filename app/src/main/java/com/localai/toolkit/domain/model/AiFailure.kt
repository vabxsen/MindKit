package com.localai.toolkit.domain.model

/**
 * Every way an on-device AI call can fail, expressed in product terms.
 *
 * Engine-specific error codes are mapped into this set at the engine boundary so that
 * no feature code ever has to know about a vendor error constant, and so that no raw
 * stack trace can reach a normal user.
 */
sealed interface AiFailure {
    /** Technical text kept for the developer "verbose errors" option only. */
    val technicalDetail: String?

    /** The device cannot run this feature at all. */
    data class Unsupported(override val technicalDetail: String? = null) : AiFailure

    /** The feature works here but the model has not been downloaded yet. */
    data class ModelNotDownloaded(override val technicalDetail: String? = null) : AiFailure

    /** A model download was attempted and failed. */
    data class DownloadFailed(override val technicalDetail: String? = null) : AiFailure

    /** The inference engine is serving another request. */
    data class Busy(
        val retryAfterMillis: Long? = null,
        override val technicalDetail: String? = null,
    ) : AiFailure

    /** The device hit its per-app AI processing/battery allowance. */
    data class QuotaExceeded(
        val retryAfterMillis: Long? = null,
        override val technicalDetail: String? = null,
    ) : AiFailure

    /** Inference is only permitted while the app is in the foreground. */
    data class BackgroundBlocked(override val technicalDetail: String? = null) : AiFailure

    /** Input was rejected for being too long or too short. */
    data class InvalidInput(
        val tooLarge: Boolean,
        override val technicalDetail: String? = null,
    ) : AiFailure

    /** The supplied image could not be decoded or was rejected by the model. */
    data class InvalidImage(override val technicalDetail: String? = null) : AiFailure

    /** Not enough free storage to install the model. */
    data class NotEnoughStorage(override val technicalDetail: String? = null) : AiFailure

    /** AICore or Play services needs a system update before the feature can run. */
    data class NeedsSystemUpdate(override val technicalDetail: String? = null) : AiFailure

    /** The user or the app cancelled the work. */
    data class Cancelled(override val technicalDetail: String? = null) : AiFailure

    /** Nothing more specific could be determined. */
    data class Unknown(override val technicalDetail: String? = null) : AiFailure
}

/** Carries an [AiFailure] across suspending/Flow boundaries. */
class AiException(
    val failure: AiFailure,
    cause: Throwable? = null,
) : Exception(failure.technicalDetail ?: failure::class.simpleName, cause)
