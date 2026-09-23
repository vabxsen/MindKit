package com.localai.toolkit.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService

/**
 * Copies [text] to the clipboard.
 *
 * @return true when the platform accepted it. Android 13+ shows its own copy
 *   confirmation, so callers should only show a toast of their own on older versions -
 *   see [shouldShowCopyConfirmation].
 */
fun Context.copyToClipboard(label: String, text: String): Boolean {
    val clipboard = getSystemService<ClipboardManager>() ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    return true
}

/**
 * Whether the app should show its own "Copied" message.
 *
 * Android 13 and newer display a system copy confirmation; showing a second one is
 * duplicated noise.
 */
fun shouldShowCopyConfirmation(): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU

/**
 * Opens the system share sheet for [text].
 *
 * This is the only path by which content leaves the app, and it is always an explicit
 * user action: the app chooses no recipient and performs no upload of its own.
 */
fun Context.shareText(text: String, chooserTitle: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, chooserTitle))
}
