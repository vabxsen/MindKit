package com.localai.toolkit.feature.share

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.localai.toolkit.R
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.domain.model.ToolId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _uiState = MutableStateFlow(ShareRouterUiState())
    val uiState: StateFlow<ShareRouterUiState> = _uiState.asStateFlow()

    /**
     * The share is read but not consumed here.
     *
     * Consuming on entry would lose the content across a configuration change; it is
     * consumed in [route], at the moment it is handed to a tool.
     */
    private val content: SharedContent? = shareStore.pending.value

    init {
        _uiState.value = when (val current = content) {
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
    }

    /** Hands the shared content to [tool] and clears it from the store. */
    fun route(tool: ToolId) {
        val payload = when (val current = shareStore.consume() ?: content) {
            is SharedContent.Text -> HandoffPayload(target = tool, text = current.value)
            is SharedContent.Image -> HandoffPayload(target = tool, imageUri = current.uri)
            is SharedContent.Audio -> HandoffPayload(target = tool, audioUri = current.uri)
            null -> return
        }
        toolHandoff.send(payload)
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
