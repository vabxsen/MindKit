package com.localai.toolkit.feature.ocr

import com.localai.toolkit.feature.common.HistorySaveController

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.mlkit.OcrEngine
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.ImageLoader
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.core.util.titleOf
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OcrUiState(
    val imageUri: Uri? = null,
    /**
     * A small bitmap used only to show the user what they picked.
     *
     * Held separately from the recognition bitmap and capped at [PREVIEW_MAX_DIMENSION]
     * so the screen never holds a full-resolution photo in memory.
     */
    val preview: Bitmap? = null,
    val isRecognizing: Boolean = false,
    val extractedText: String = "",
    val failure: AiFailure? = null,
    /** Set when recognition ran successfully but found nothing. */
    val noTextDetected: Boolean = false,
    val savedToHistory: Boolean = false,
) {
    val hasResult: Boolean get() = extractedText.isNotBlank()

    companion object {
        const val PREVIEW_MAX_DIMENSION = 1024

        /** Resolution handed to text recognition. Higher than the preview, far below a
         *  modern camera's native size. */
        const val RECOGNITION_MAX_DIMENSION = 2048
    }
}

@HiltViewModel
class OcrViewModel @Inject constructor(
    private val imageLoader: ImageLoader,
    private val ocrEngine: OcrEngine,
    private val historyRepository: HistoryRepository,
    private val toolHandoff: ToolHandoff,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    private val historySave = HistorySaveController(historyRepository, viewModelScope)
    val saveFeedback = historySave.feedback

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var recognitionJob: Job? = null

    init {
        // An image shared into the app, or handed over from another tool, arrives here.
        toolHandoff.consume(ToolId.OCR)?.imageUri?.let(::onImageSelected)
    }

    fun onImageSelected(uri: Uri) {
        recognitionJob?.cancel()
        _uiState.value = OcrUiState(imageUri = uri, isRecognizing = true)

        recognitionJob = viewModelScope.launch {
            try {
                // Decoding and recognition both happen off the main thread, and the
                // recognition bitmap is downsampled first so a 48 MP photo cannot
                // exhaust memory.
                val preview = imageLoader.load(uri, OcrUiState.PREVIEW_MAX_DIMENSION)
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(preview = preview)

                val bitmap = imageLoader.load(uri, OcrUiState.RECOGNITION_MAX_DIMENSION)
                currentCoroutineContext().ensureActive()
                if (bitmap == null) {
                    _uiState.value = _uiState.value.copy(
                        isRecognizing = false,
                        failure = AiFailure.InvalidImage(),
                    )
                    return@launch
                }

                // Native ML Kit work can outlive cancellation of Task.await(). Let
                // reference ownership govern bitmap lifetime instead of recycling
                // memory the recognizer (or Compose preview) may still be reading.
                val recognized = ocrEngine.recognize(bitmap)
                currentCoroutineContext().ensureActive()

                _uiState.value = _uiState.value.copy(
                    isRecognizing = false,
                    extractedText = recognized.fullText,
                    noTextDetected = recognized.isEmpty,
                )
            } catch (e: AiException) {
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(isRecognizing = false, failure = e.failure)
            }
        }
    }

    fun onRetry() {
        _uiState.value.imageUri?.let(::onImageSelected)
    }

    fun onClear() {
        recognitionJob?.cancel()
        _uiState.value = OcrUiState()
    }

    fun onSave() {
        val state = _uiState.value
        val text = state.extractedText
        if (!state.hasResult || state.isRecognizing || state.savedToHistory) return
        historySave.save(
            item = HistoryItem(
                type = HistoryType.OCR,
                title = titleOf(text),
                // The image itself is never stored - only a note that one was used.
                inputPreview = previewOf(text),
                output = text,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
            isCurrent = { _uiState.value.extractedText == state.extractedText && _uiState.value.imageUri == state.imageUri && !_uiState.value.isRecognizing },
        ) { saved ->
            _uiState.value = _uiState.value.copy(savedToHistory = saved)
        }
    }

    /** Hands the extracted text to another tool, e.g. Extract Text then Summarize. */
    fun sendTo(target: ToolId) {
        val text = _uiState.value.extractedText
        if (text.isBlank()) return
        toolHandoff.send(HandoffPayload(target = target, text = text))
    }

    override fun onCleared() {
        // Cancels in-flight recognition when the screen goes away, so inference does not
        // continue for a screen nobody is looking at. Compose/native references may
        // outlive this owner, so bitmap memory is reclaimed normally, not recycled.
        recognitionJob?.cancel()
    }
}
