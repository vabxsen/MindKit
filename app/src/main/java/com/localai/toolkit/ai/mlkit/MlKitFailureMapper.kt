package com.localai.toolkit.ai.mlkit

import com.google.mlkit.common.MlKitException
import com.localai.toolkit.domain.model.AiFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Turns an ML Kit throwable into a product-level [AiFailure].
 *
 * This is the only place in the app that knows ML Kit's error codes exist. Screens work
 * with [AiFailure] and render a sentence the user can act on, which is what keeps raw
 * exception text off the screen.
 */
internal fun Throwable.toAiFailure(): AiFailure {
    // Cancellation is cooperative control flow, not an error to report.
    if (this is CancellationException) return AiFailure.Cancelled(technicalDetail())

    return when (this) {
        is MlKitException -> when (errorCode) {
            MlKitException.UNSUPPORTED,
            MlKitException.UNIMPLEMENTED,
            -> AiFailure.Unsupported(technicalDetail())

            MlKitException.NOT_ENOUGH_SPACE -> AiFailure.NotEnoughStorage(technicalDetail())

            // The model is not on the device yet. NOT_FOUND is what ML Kit reports when a
            // translation model has not been downloaded.
            MlKitException.NOT_FOUND,
            MlKitException.FAILED_PRECONDITION,
            -> AiFailure.ModelNotDownloaded(technicalDetail())

            MlKitException.NETWORK_ISSUE,
            MlKitException.UNAVAILABLE,
            -> AiFailure.DownloadFailed(technicalDetail())

            MlKitException.RESOURCE_EXHAUSTED -> AiFailure.QuotaExceeded(
                technicalDetail = technicalDetail(),
            )

            MlKitException.CANCELLED -> AiFailure.Cancelled(technicalDetail())

            MlKitException.INVALID_ARGUMENT,
            MlKitException.OUT_OF_RANGE,
            -> AiFailure.InvalidInput(tooLarge = false, technicalDetail = technicalDetail())

            MlKitException.MODEL_HASH_MISMATCH,
            MlKitException.MODEL_INCOMPATIBLE_WITH_TFLITE,
            -> AiFailure.DownloadFailed(technicalDetail())

            else -> AiFailure.Unknown(technicalDetail())
        }

        // A model download with no connection surfaces as a plain IO failure.
        is IOException -> AiFailure.DownloadFailed(technicalDetail())

        else -> AiFailure.Unknown(technicalDetail())
    }
}

/**
 * A short technical string for the developer "verbose errors" option.
 *
 * Deliberately not a stack trace: it is the exception type, the ML Kit error code when
 * there is one, and the message.
 */
private fun Throwable.technicalDetail(): String {
    val code = (this as? MlKitException)?.let { " code=${it.errorCode}" }.orEmpty()
    return "${this::class.simpleName}$code: ${message.orEmpty()}".trim()
}
