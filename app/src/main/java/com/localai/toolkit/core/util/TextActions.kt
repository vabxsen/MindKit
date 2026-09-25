package com.localai.toolkit.core.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.getSystemService

/**
 * Copies [text] to the clipboard.
 *
 * @return true when the platform accepted it. Android 13+ shows its own copy
 *   confirmation, so callers should only show a confirmation of their own on older versions -
 *   see [shouldShowCopyConfirmation].
 */
fun Context.copyToClipboard(label: String, text: String): Boolean {
    return try {
        val clipboard = getSystemService<ClipboardManager>() ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    } catch (_: RuntimeException) {
        // Platform policy/service failures (including oversized binder payloads)
        // must not crash a result button or be reported as a successful copy.
        false
    }
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
 * Always an explicit user action: the app chooses no recipient and performs no upload
 * of its own. Returns true when Android accepts the chooser launch, not when a
 * recipient receives the text.
 */
fun Context.shareText(text: String, chooserTitle: String? = null): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return try {
        val chooser = Intent.createChooser(send, chooserTitle)
        if (!hasActivityContext()) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(chooser)
        true
    } catch (_: RuntimeException) {
        false
    }
}

private fun Context.hasActivityContext(): Boolean {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return true
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return current is Activity
}
