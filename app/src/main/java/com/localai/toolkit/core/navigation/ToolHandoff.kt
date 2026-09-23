package com.localai.toolkit.core.navigation

import android.net.Uri
import com.localai.toolkit.domain.model.ToolId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What one tool hands to another.
 *
 * Extracted OCR text can be long and image URIs must not be re-encoded into a route, so
 * payloads travel in memory rather than as navigation arguments. Nothing here is
 * persisted; the payload lives only until the destination consumes it or the process ends.
 */
data class HandoffPayload(
    val target: ToolId,
    val text: String? = null,
    val imageUri: Uri? = null,
    val audioUri: Uri? = null,
)

/**
 * A one-shot channel for tool-to-tool navigation.
 *
 * The sender calls [send] immediately before navigating; the destination's ViewModel
 * calls [consume] once during initialisation. [consume] clears the payload so that
 * returning to the screen later, or a configuration change, does not replay it.
 */
@Singleton
class ToolHandoff @Inject constructor() {

    private val pending = MutableStateFlow<HandoffPayload?>(null)

    fun send(payload: HandoffPayload) {
        pending.value = payload
    }

    /** Returns and clears a payload addressed to [target], or null when there is none. */
    fun consume(target: ToolId): HandoffPayload? {
        val current = pending.value ?: return null
        if (current.target != target) return null
        pending.value = null
        return current
    }

    /** Drops any pending payload, e.g. when the user cancels a share. */
    fun clear() {
        pending.value = null
    }
}
