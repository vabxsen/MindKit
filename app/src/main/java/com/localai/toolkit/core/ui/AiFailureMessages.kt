package com.localai.toolkit.core.ui

import android.content.Context
import androidx.annotation.StringRes
import com.localai.toolkit.R
import com.localai.toolkit.domain.model.AiFailure

/**
 * The single mapping from failure to user-facing sentence.
 *
 * Centralised so that every tool reports the same condition the same way, and so that no
 * screen can accidentally render an exception message.
 */
@StringRes
fun AiFailure.messageRes(): Int = when (this) {
    is AiFailure.Unsupported -> R.string.error_unsupported
    is AiFailure.ModelNotDownloaded -> R.string.error_model_not_downloaded
    is AiFailure.DownloadFailed -> R.string.error_download_failed
    is AiFailure.Busy -> R.string.error_busy
    is AiFailure.QuotaExceeded -> R.string.error_quota_exceeded
    is AiFailure.BackgroundBlocked -> R.string.error_background_blocked
    is AiFailure.InvalidInput ->
        if (tooLarge) R.string.error_input_too_long else R.string.error_input_too_short
    is AiFailure.InvalidImage -> R.string.error_invalid_image
    is AiFailure.NotEnoughStorage -> R.string.error_not_enough_storage
    is AiFailure.NeedsSystemUpdate -> R.string.error_needs_system_update
    is AiFailure.Cancelled -> R.string.error_cancelled
    is AiFailure.Unknown -> R.string.error_unknown
}

fun AiFailure.message(context: Context): String = context.getString(messageRes())

/**
 * The technical line shown beneath the friendly message.
 *
 * Returns null unless the user has switched on verbose errors in Settings, which is what
 * keeps vendor error codes away from normal users while leaving them one toggle away for
 * anyone debugging a device.
 */
fun AiFailure.technicalDetailOrNull(context: Context, verboseErrors: Boolean): String? {
    if (!verboseErrors) return null
    val detail = technicalDetail ?: return null
    return context.getString(R.string.error_technical_detail, detail)
}

/**
 * Whether offering a "Download model" action makes sense for this failure.
 *
 * Retrying a download that failed is reasonable; retrying an unsupported device is not.
 */
val AiFailure.offersModelDownload: Boolean
    get() = this is AiFailure.ModelNotDownloaded || this is AiFailure.DownloadFailed

/** Whether a plain retry is likely to help. */
val AiFailure.offersRetry: Boolean
    get() = when (this) {
        is AiFailure.Busy,
        is AiFailure.QuotaExceeded,
        is AiFailure.Unknown,
        is AiFailure.BackgroundBlocked,
        -> true
        else -> false
    }
