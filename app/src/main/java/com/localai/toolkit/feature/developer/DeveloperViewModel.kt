package com.localai.toolkit.feature.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.AskRequest
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.core.util.titleOf
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.domain.usecase.devtools.DevTools
import com.localai.toolkit.domain.usecase.devtools.JsonParseException
import com.localai.toolkit.domain.usecase.devtools.formatJson
import com.localai.toolkit.domain.usecase.devtools.minifyJson
import com.localai.toolkit.domain.usecase.devtools.parseJson
import com.localai.toolkit.feature.common.GenAiFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeveloperUiState(
    val input: String = "",
    val output: String = "",
    /** Set when a deterministic tool rejected its input, e.g. malformed JSON. */
    val inputError: String? = null,
    val isOutputMonospace: Boolean = true,
    // Explain Error / Explain Code
    val errorLanguage: ErrorLanguage = ErrorLanguage.ANDROID,
    val isGenerating: Boolean = false,
    val failure: AiFailure? = null,
    val savedToHistory: Boolean = false,
    // Tool-specific options
    val base64Decode: Boolean = false,
    val urlDecode: Boolean = false,
    val hashAlgorithm: DevTools.HashAlgorithm = DevTools.HashAlgorithm.SHA256,
    val uuidCount: Int = 1,
) {
    val hasOutput: Boolean get() = output.isNotBlank()
}

@HiltViewModel
class DeveloperViewModel @Inject constructor(
    private val engine: AiEngine,
    private val historyRepository: HistoryRepository,
    capabilityManager: DeviceAiCapabilityManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val gate = GenAiFeatureGate(AiTask.ASK, capabilityManager, engine, viewModelScope)

    /** Only meaningful for the two AI-backed tools. */
    val capability: StateFlow<AiCapability> = gate.capability
    val downloadState = gate.downloadState

    private val _uiState = MutableStateFlow(DeveloperUiState())
    val uiState: StateFlow<DeveloperUiState> = _uiState.asStateFlow()

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var job: Job? = null

    fun onInputChange(value: String) {
        _uiState.value = _uiState.value.copy(
            input = value,
            inputError = null,
            savedToHistory = false,
        )
    }

    fun onErrorLanguageChange(language: ErrorLanguage) {
        _uiState.value = _uiState.value.copy(errorLanguage = language)
    }

    fun onBase64DirectionChange(decode: Boolean) {
        _uiState.value = _uiState.value.copy(base64Decode = decode, output = "", inputError = null)
    }

    fun onUrlDirectionChange(decode: Boolean) {
        _uiState.value = _uiState.value.copy(urlDecode = decode, output = "", inputError = null)
    }

    fun onHashAlgorithmChange(algorithm: DevTools.HashAlgorithm) {
        _uiState.value = _uiState.value.copy(hashAlgorithm = algorithm)
        if (_uiState.value.input.isNotBlank()) run(DeveloperTool.HASH_GENERATOR)
    }

    fun onUuidCountChange(count: Int) {
        _uiState.value = _uiState.value.copy(uuidCount = count)
    }

    fun onClear() {
        job?.cancel()
        _uiState.value = DeveloperUiState(
            errorLanguage = _uiState.value.errorLanguage,
            hashAlgorithm = _uiState.value.hashAlgorithm,
            base64Decode = _uiState.value.base64Decode,
            urlDecode = _uiState.value.urlDecode,
            uuidCount = _uiState.value.uuidCount,
        )
    }

    /** Runs [tool] against the current input. */
    fun run(tool: DeveloperTool) {
        when (tool) {
            DeveloperTool.EXPLAIN_ERROR -> explain(isCode = false)
            DeveloperTool.EXPLAIN_CODE -> explain(isCode = true)
            else -> runDeterministic(tool)
        }
    }

    private fun runDeterministic(tool: DeveloperTool) {
        val state = _uiState.value
        val input = state.input

        // UUID generation is the one tool that does not need input.
        if (input.isBlank() && tool != DeveloperTool.UUID_GENERATOR) {
            _uiState.value = state.copy(output = "", inputError = null)
            return
        }

        val result: DeterministicResult = when (tool) {
            DeveloperTool.JSON_FORMATTER -> runCatching {
                DeterministicResult.Ok(formatJson(parseJson(input)))
            }.getOrElse { it.toJsonError() }

            DeveloperTool.JSON_VALIDATOR -> runCatching {
                val parsed = parseJson(input)
                DeterministicResult.Ok(
                    buildString {
                        appendLine(VALID_JSON_MARKER)
                        appendLine()
                        append(minifyJson(parsed))
                    },
                )
            }.getOrElse { it.toJsonError() }

            DeveloperTool.BASE64 -> if (state.base64Decode) {
                DevTools.base64Decode(input)
                    ?.let { DeterministicResult.Ok(it) }
                    ?: DeterministicResult.Error(INVALID_BASE64_MARKER)
            } else {
                DeterministicResult.Ok(DevTools.base64Encode(input))
            }

            DeveloperTool.URL_CODEC -> if (state.urlDecode) {
                DevTools.urlDecode(input)
                    ?.let { DeterministicResult.Ok(it) }
                    ?: DeterministicResult.Error(INVALID_URL_MARKER)
            } else {
                DeterministicResult.Ok(DevTools.urlEncode(input))
            }

            DeveloperTool.JWT_DECODER -> DevTools.decodeJwt(input)
                ?.let { decoded ->
                    DeterministicResult.Ok(
                        buildString {
                            appendLine("HEADER")
                            appendLine(DevTools.prettyPrintIfJson(decoded.header))
                            appendLine()
                            appendLine("PAYLOAD")
                            appendLine(DevTools.prettyPrintIfJson(decoded.payload))
                            decoded.expiresAt?.let { expiry ->
                                appendLine()
                                appendLine("EXPIRES")
                                appendLine(expiry.localTime)
                                appendLine(
                                    if (decoded.isExpired == true) "Expired" else "Not expired",
                                )
                            }
                            appendLine()
                            appendLine("SIGNATURE (not verified)")
                            append(decoded.signature.ifBlank { "none" })
                        },
                    )
                }
                ?: DeterministicResult.Error(INVALID_JWT_MARKER)

            DeveloperTool.UUID_GENERATOR -> DeterministicResult.Ok(
                DevTools.generateUuids(state.uuidCount).joinToString("\n"),
            )

            DeveloperTool.HASH_GENERATOR -> DeterministicResult.Ok(
                DevTools.hash(input, state.hashAlgorithm),
            )

            DeveloperTool.TIMESTAMP -> {
                val parsed = DevTools.parseEpoch(input) ?: DevTools.parseIso8601(input)
                parsed?.let {
                    DeterministicResult.Ok(
                        buildString {
                            appendLine("Epoch seconds: ${it.epochSeconds}")
                            appendLine("Epoch millis:  ${it.epochMillis}")
                            appendLine("ISO 8601 UTC:  ${it.iso8601Utc}")
                            append("Local time:    ${it.localTime}")
                        },
                    )
                } ?: DeterministicResult.Error(INVALID_TIMESTAMP_MARKER)
            }

            DeveloperTool.EXPLAIN_ERROR, DeveloperTool.EXPLAIN_CODE ->
                DeterministicResult.Ok("")
        }

        _uiState.value = when (result) {
            is DeterministicResult.Ok -> state.copy(output = result.text, inputError = null)
            is DeterministicResult.Error -> state.copy(output = "", inputError = result.message)
        }
    }

    /** Inserts the current time, for the timestamp tool's "now" shortcut. */
    fun useCurrentTimestamp() {
        onInputChange(DevTools.now().epochSeconds.toString())
        run(DeveloperTool.TIMESTAMP)
    }

    /**
     * Asks the on-device model to explain an error or a snippet of code.
     *
     * The prompt asks for four fixed sections. The Prompt API on this ML Kit version has
     * no schema-constrained output that the app can rely on, so the structure is
     * requested in the prompt and the response is shown as the model returns it, rather
     * than being parsed into fields the model never promised to produce.
     */
    private fun explain(isCode: Boolean) {
        val state = _uiState.value
        if (state.input.isBlank() || state.isGenerating) return

        job?.cancel()
        _uiState.value = state.copy(
            isGenerating = true,
            output = "",
            failure = null,
            savedToHistory = false,
        )

        val prompt = if (isCode) {
            buildCodeExplanationPrompt(state.input, state.errorLanguage)
        } else {
            buildErrorExplanationPrompt(state.input, state.errorLanguage)
        }

        job = viewModelScope.launch {
            try {
                engine.generateText(AskRequest(prompt = prompt)).collect { chunk ->
                    _uiState.value = _uiState.value.copy(
                        output = chunk.text,
                        isGenerating = !chunk.isFinal,
                    )
                }
                _uiState.value = _uiState.value.copy(isGenerating = false)
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(isGenerating = false, failure = e.failure)
            }
        }
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasOutput) return
        viewModelScope.launch {
            val saved = historyRepository.save(
                HistoryItem(
                    type = HistoryType.DEVELOPER,
                    title = titleOf(state.input),
                    inputPreview = previewOf(state.input),
                    output = state.output,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            _uiState.value = _uiState.value.copy(savedToHistory = saved != null)
        }
    }

    fun onDownloadModel() = gate.download()

    fun onRetryCapabilityCheck() = gate.refresh()

    override fun onCleared() {
        job?.cancel()
        gate.release()
        super.onCleared()
    }

    private sealed interface DeterministicResult {
        data class Ok(val text: String) : DeterministicResult
        data class Error(val message: String) : DeterministicResult
    }

    private fun Throwable.toJsonError(): DeterministicResult =
        if (this is JsonParseException) {
            DeterministicResult.Error(error.toString())
        } else {
            DeterministicResult.Error(message ?: INVALID_JSON_MARKER)
        }

    companion object {
        // Markers the screen turns into localised strings. Kept as constants rather than
        // string resources here so the ViewModel stays free of Android resources and
        // testable on the JVM.
        const val VALID_JSON_MARKER = "VALID_JSON"
        const val INVALID_JSON_MARKER = "INVALID_JSON"
        const val INVALID_BASE64_MARKER = "INVALID_BASE64"
        const val INVALID_URL_MARKER = "INVALID_URL"
        const val INVALID_JWT_MARKER = "INVALID_JWT"
        const val INVALID_TIMESTAMP_MARKER = "INVALID_TIMESTAMP"
    }
}

/**
 * The Explain Error prompt.
 *
 * Asks for named sections so the answer is scannable, and tells the model to say when it
 * is unsure rather than inventing a cause - on-device models are small, and a confident
 * wrong diagnosis wastes more of a developer's time than an honest "not certain".
 */
internal fun buildErrorExplanationPrompt(errorText: String, language: ErrorLanguage): String =
    """
    You are helping a developer understand an error from ${language.promptName}.

    Answer with exactly these four sections, in this order, each on its own line:
    Likely Cause
    Explanation
    Possible Fixes
    What to Check

    Keep it short and concrete. If the error text is not enough to be sure, say so in
    Likely Cause instead of guessing.

    Error:
    $errorText
    """.trimIndent()

/** The Explain Code prompt. Same structure, different task. */
internal fun buildCodeExplanationPrompt(code: String, language: ErrorLanguage): String =
    """
    You are helping a developer understand a piece of ${language.promptName} code.

    Answer with exactly these four sections, in this order, each on its own line:
    What It Does
    How It Works
    Things To Watch Out For
    Suggested Improvements

    Be specific about this code rather than general. Keep it short.

    Code:
    $code
    """.trimIndent()
