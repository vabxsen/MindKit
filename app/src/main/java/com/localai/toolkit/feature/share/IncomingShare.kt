package com.localai.toolkit.feature.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Content another app sent to MindKit through the system share sheet. */
sealed interface SharedContent {
    data class Text(val value: String) : SharedContent
    data class Image(val uri: Uri) : SharedContent
    data class Audio(val uri: Uri) : SharedContent
}

/**
 * Holds the share the app was launched with until a screen consumes it.
 *
 * In memory only. Shared content is not written to disk anywhere along this path: it goes
 * from the intent, to the action screen, to whichever tool the user picks.
 */
@Singleton
class IncomingShareStore @Inject constructor() {

    private val _pending = MutableStateFlow<SharedContent?>(null)
    val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

    fun set(content: SharedContent) {
        _pending.value = content
    }

    fun consume(): SharedContent? = _pending.value.also { _pending.value = null }

    fun clear() {
        _pending.value = null
    }
}

/**
 * Extracts shareable content from an incoming intent.
 *
 * Returns null for anything the app cannot actually do something with, so an unhandled
 * MIME type opens Home rather than an action screen with no actions.
 */
fun Intent.toSharedContent(): SharedContent? {
    if (action != Intent.ACTION_SEND) return null

    val mimeType = type.orEmpty()

    return when {
        mimeType.startsWith("text/") -> {
            val text = getStringExtra(Intent.EXTRA_TEXT)
                // Some apps share a subject with no body; a subject alone is still text
                // worth acting on.
                ?: getStringExtra(Intent.EXTRA_SUBJECT)
            text?.takeIf { it.isNotBlank() }?.let { SharedContent.Text(it) }
        }

        mimeType.startsWith("image/") -> streamUri()?.let { SharedContent.Image(it) }

        mimeType.startsWith("audio/") -> streamUri()?.let { SharedContent.Audio(it) }

        else -> null
    }
}

/**
 * Reads EXTRA_STREAM through [IntentCompat].
 *
 * The untyped `getParcelableExtra` is deprecated from API 33; the compat helper picks the
 * right call per API level, which is better than suppressing the warning.
 */
private fun Intent.streamUri(): Uri? =
    IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
