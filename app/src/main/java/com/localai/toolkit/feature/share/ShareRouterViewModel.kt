package com.localai.toolkit.feature.share

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.R
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.domain.model.ToolId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ShareRouterUiState(
    @param:StringRes val kindLabelRes: Int = R.string.share_kind_text,
    val previewText: String = "",
    val actions: List<ToolId> = emptyList(),
)

@HiltViewModel
class ShareRouterViewModel @Inject constructor(
    private val shareStore: IncomingShareStore,
    private val toolHandoff: ToolHandoff,
) : ViewModel() {

    // A second share can arrive while this same router is still visible.
    val uiState = shareStore.pending.map(::stateFor)
        .stateIn(viewModelScope, SharingStarted.Eagerly, stateFor(shareStore.pending.value))

    private fun stateFor(current: SharedContent?): ShareRouterUiState = when (current) {
        is SharedContent.Text -> ShareRouterUiState(
            kindLabelRes = R.string.share_kind_text,
            previewText = previewOf(current.value, maxChars = 400),
            actions = TEXT_ACTIONS,
        )

        is SharedContent.Image -> ShareRouterUiState(
            kindLabelRes = R.string.share_kind_image,
            previewText = "",
            actions = IMAGE_ACTIONS,
        )

        is SharedContent.Audio -> ShareRouterUiState(
            kindLabelRes = R.string.share_kind_audio,
            previewText = "",
            actions = AUDIO_ACTIONS,
        )

        null -> ShareRouterUiState(actions = emptyList())
    }

    /** Hands the shared content to [tool] and clears it from the store. */
    fun route(tool: ToolId): Boolean {
        val current = shareStore.pending.value ?: return false
        if (tool !in stateFor(current).actions) return false
        val payload = when (current) {
            is SharedContent.Text -> HandoffPayload(target = tool, text = current.value)
            is SharedContent.Image -> HandoffPayload(target = tool, imageUri = current.uri)
            is SharedContent.Audio -> HandoffPayload(target = tool, audioUri = current.uri)
        }
        shareStore.consume()
        toolHandoff.send(payload)
        return true
    }

    fun cancel() {
        shareStore.clear()
        toolHandoff.clear()
    }

    private companion object {
        /** Ordered by how often each is the reason someone shares text into the app. */
        val TEXT_ACTIONS = listOf(
            ToolId.SUMMARIZE,
            ToolId.REWRITE,
            ToolId.PROOFREAD,
            ToolId.TRANSLATE,
            ToolId.ASK,
        )

        val IMAGE_ACTIONS = listOf(ToolId.OCR, ToolId.IMAGE)

        /**
         * Only Transcribe can act on audio.
         *
         * Whether it will actually succeed depends on the device supporting file input,
         * which the Transcribe screen reports for itself rather than being guessed here.
         */
        val AUDIO_ACTIONS = listOf(ToolId.TRANSCRIBE)
    }
}
