package com.localai.toolkit.feature.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.AnalyzeImageRequest
import com.localai.toolkit.ai.engine.DescribeImageRequest
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.decodeDownsampledBitmap
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.core.util.titleOf
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.feature.common.GenAiFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the two image tasks the user asked for. */
enum class ImageMode { DESCRIBE, ASK }

data class ImageUiState(
    val imageUri: Uri? = null,
    val preview: Bitmap? = null,
    val mode: ImageMode = ImageMode.DESCRIBE,
    val question: String = "",
    val output: String = "",
    val isWorking: Boolean = false,
    val isStreaming: Boolean = false,
    val failure: AiFailure? = null,
    val savedToHistory: Boolean = false,
) {
    val hasImage: Boolean get() = preview != null
    val hasResult: Boolean get() = output.isNotBlank()
    val canRun: Boolean
        get() = hasImage && !isWorking &&
            (mode == ImageMode.DESCRIBE || question.isNotBlank())

    companion object {
        /**
         * Resolution handed to the model.
         *
         * Image description and multimodal prompting do not benefit from full camera
         * resolution, and a large bitmap crossing the AICore boundary is both slow and a
         * memory risk. 1024 on the longest edge is ample for describing a scene or
         * reading a screenshot.
         */
        const val MODEL_MAX_DIMENSION = 1024
    }
}

@HiltViewModel
class ImageViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: AiEngine,
    private val historyRepository: HistoryRepository,
    private val toolHandoff: ToolHandoff,
    capabilityManager: DeviceAiCapabilityManager,
    settingsRepository: SettingsRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val describeGate =
        GenAiFeatureGate(AiTask.IMAGE_DESCRIPTION, capabilityManager, engine, viewModelScope)
    private val askGate =
        GenAiFeatureGate(AiTask.IMAGE_QUESTION, capabilityManager, engine, viewModelScope)

    /** The gate that matches the selected mode, so the UI gates on the right feature. */
    val describeCapability: StateFlow<AiCapability> = describeGate.capability
    val askCapability: StateFlow<AiCapability> = askGate.capability
    val describeDownloadState = describeGate.downloadState
    val askDownloadState = askGate.downloadState

    private val _uiState = MutableStateFlow(ImageUiState())
    val uiState: StateFlow<ImageUiState> = _uiState.asStateFlow()

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var job: Job? = null

    init {
        toolHandoff.consume(ToolId.IMAGE)?.imageUri?.let(::onImageSelected)
    }

    fun onImageSelected(uri: Uri) {
        job?.cancel()
        _uiState.value.preview?.recycle()
        _uiState.value = ImageUiState(imageUri = uri, mode = _uiState.value.mode)

        viewModelScope.launch {
            val bitmap = withContext(ioDispatcher) {
                context.decodeDownsampledBitmap(uri, ImageUiState.MODEL_MAX_DIMENSION)
            }
            _uiState.value = if (bitmap == null) {
                _uiState.value.copy(failure = AiFailure.InvalidImage())
            } else {
                _uiState.value.copy(preview = bitmap)
            }
        }
    }

    fun onModeChange(mode: ImageMode) {
        _uiState.value = _uiState.value.copy(mode = mode, output = "", failure = null)
    }

    fun onQuestionChange(value: String) {
        _uiState.value = _uiState.value.copy(question = value)
    }

    fun onRun() {
        val state = _uiState.value
        val bitmap = state.preview ?: return
        if (!state.canRun) return

        job?.cancel()
        _uiState.value = state.copy(
            isWorking = true,
            output = "",
            failure = null,
            savedToHistory = false,
        )

        job = viewModelScope.launch {
            try {
                when (state.mode) {
                    ImageMode.DESCRIBE -> {
                        val description = engine.describeImage(DescribeImageRequest(bitmap))
                        _uiState.value = _uiState.value.copy(
                            isWorking = false,
                            output = description,
                        )
                    }

                    ImageMode.ASK -> {
                        // Streamed, because a multimodal answer can take a while and
                        // watching it arrive is far better than a blank wait.
                        _uiState.value = _uiState.value.copy(isStreaming = true)
                        engine.analyzeImage(
                            AnalyzeImageRequest(bitmap = bitmap, prompt = state.question),
                        ).collect { chunk ->
                            _uiState.value = _uiState.value.copy(
                                output = chunk.text,
                                isStreaming = !chunk.isFinal,
                                isWorking = !chunk.isFinal,
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            isWorking = false,
                            isStreaming = false,
                        )
                    }
                }
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(
                    isWorking = false,
                    isStreaming = false,
                    failure = e.failure,
                )
            }
        }
    }

    fun onStop() {
        job?.cancel()
        job = null
        _uiState.value = _uiState.value.copy(isWorking = false, isStreaming = false)
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasResult) return
        viewModelScope.launch {
            val saved = historyRepository.save(
                HistoryItem(
                    type = HistoryType.IMAGE_DESCRIPTION,
                    title = titleOf(
                        state.question.ifBlank { state.output },
                    ),
                    // The image is never stored; only what the user asked about it.
                    inputPreview = previewOf(state.question.ifBlank { "Image" }),
                    output = state.output,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    metadata = state.mode.name,
                ),
            )
            _uiState.value = _uiState.value.copy(savedToHistory = saved != null)
        }
    }

    /** Hands the current image to Extract Text, or the answer text to another tool. */
    fun sendImageToOcr() {
        val uri = _uiState.value.imageUri ?: return
        toolHandoff.send(HandoffPayload(target = ToolId.OCR, imageUri = uri))
    }

    fun sendTextTo(target: ToolId) {
        val text = _uiState.value.output
        if (text.isBlank()) return
        toolHandoff.send(HandoffPayload(target = target, text = text))
    }

    fun onClear() {
        job?.cancel()
        _uiState.value.preview?.recycle()
        _uiState.value = ImageUiState(mode = _uiState.value.mode)
    }

    fun onDownloadModel() {
        if (_uiState.value.mode == ImageMode.DESCRIBE) describeGate.download() else askGate.download()
    }

    fun onRetryCapabilityCheck() {
        describeGate.refresh()
        askGate.refresh()
    }

    override fun onCleared() {
        job?.cancel()
        _uiState.value.preview?.recycle()
        describeGate.release()
        askGate.release()
        super.onCleared()
    }
}
