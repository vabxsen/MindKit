package com.localai.toolkit.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
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
 * The store is in memory. MainActivity also saves the unconsumed payload in Android's
 * activity saved state so the latest share survives process death. It is not added to
 * app history or a separate content cache.
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

/** Save only the normalized pending payload, not arbitrary extras from another app. */
internal fun SharedContent.toSavedState(): Bundle = Bundle().apply {
    when (val content = this@toSavedState) {
        is SharedContent.Text -> { putString("kind", "text"); putString("value", content.value) }
        is SharedContent.Image -> { putString("kind", "image"); putString("value", content.uri.toString()) }
        is SharedContent.Audio -> { putString("kind", "audio"); putString("value", content.uri.toString()) }
    }
}

/** URI grants are still owned by Android; saving a URI does not create a permission. */
internal fun Bundle.toSharedContent(): SharedContent? {
    val value = getString("value")?.takeIf { it.isNotBlank() } ?: return null
    return when (getString("kind")) {
        "text" -> SharedContent.Text(value)
        "image" -> SharedContent.Image(value.toUri())
        "audio" -> SharedContent.Audio(value.toUri())
        else -> null
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
            val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                // Some apps share a subject with no body; a subject alone is still text
                // worth acting on.
                ?: getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
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
        ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
