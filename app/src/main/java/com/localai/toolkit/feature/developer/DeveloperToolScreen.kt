package com.localai.toolkit.feature.developer

import com.localai.toolkit.feature.common.HistorySaveFeedback
import com.localai.toolkit.feature.common.ObserveHistorySaveFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.component.ResultCard
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.MonospaceBody
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.ui.messageRes
import com.localai.toolkit.core.ui.offersRetry
import com.localai.toolkit.core.ui.technicalDetailOrNull
import com.localai.toolkit.core.ui.rememberTextActionHandler
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.usecase.devtools.DevTools
import com.localai.toolkit.feature.common.GenAiGate
import com.localai.toolkit.feature.common.OptionGroup
import com.localai.toolkit.feature.common.gateStateOf

@Composable
fun DeveloperToolScreen(
    tool: DeveloperTool,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeveloperViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    DeveloperToolContent(
        tool = tool,
        state = state,
        capability = capability,
        downloadState = downloadState,
        verboseErrors = verboseErrors,
        saveFeedback = viewModel.saveFeedback,
        onInputChange = viewModel::onInputChange,
        onRun = { viewModel.run(tool) },
        onClear = viewModel::onClear,
        onSave = viewModel::onSave,
        onErrorLanguageChange = viewModel::onErrorLanguageChange,
        onBase64DirectionChange = viewModel::onBase64DirectionChange,
        onUrlDirectionChange = viewModel::onUrlDirectionChange,
        onHashAlgorithmChange = viewModel::onHashAlgorithmChange,
        onUuidCountChange = viewModel::onUuidCountChange,
        onUseNow = viewModel::useCurrentTimestamp,
        onDownload = viewModel::onDownloadModel,
        onRetryCheck = viewModel::onRetryCapabilityCheck,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun DeveloperToolContent(
    tool: DeveloperTool,
    state: DeveloperUiState,
    capability: AiCapability,
    downloadState: ModelDownloadState,
    verboseErrors: Boolean,
    onInputChange: (String) -> Unit,
    onRun: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onErrorLanguageChange: (ErrorLanguage) -> Unit,
    onBase64DirectionChange: (Boolean) -> Unit,
    onUrlDirectionChange: (Boolean) -> Unit,
    onHashAlgorithmChange: (DevTools.HashAlgorithm) -> Unit,
    onUuidCountChange: (Int) -> Unit,
    onUseNow: () -> Unit,
    onDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    saveFeedback: Flow<HistorySaveFeedback> = emptyFlow(),
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    ObserveHistorySaveFeedback(saveFeedback, snackbarHostState)
    val textActions = rememberTextActionHandler(snackbarHostState)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = stringResource(tool.titleRes),
                onNavigateUp = onNavigateUp,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.ScreenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            val body: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    if (tool != DeveloperTool.UUID_GENERATOR) {
                        OutlinedTextField(
                            value = state.input,
                            onValueChange = onInputChange,
                            label = { Text(stringResource(tool.inputHintRes())) },
                            isError = state.inputError != null,
                            textStyle = if (tool.usesMonospaceInput()) {
                                MonospaceBody
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (tool.usesMonospaceInput()) 200.dp else 120.dp)
                                .padding(top = Spacing.M),
                        )
                    }

                    ToolOptions(
                        tool = tool,
                        state = state,
                        onErrorLanguageChange = onErrorLanguageChange,
                        onBase64DirectionChange = onBase64DirectionChange,
                        onUrlDirectionChange = onUrlDirectionChange,
                        onHashAlgorithmChange = onHashAlgorithmChange,
                        onUuidCountChange = onUuidCountChange,
                    )

                    if (tool == DeveloperTool.TIMESTAMP) {
                        OutlinedButton(onClick = onUseNow, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.dev_timestamp_now))
                        }
                    }

                    Button(
                        onClick = onRun,
                        enabled = !state.isGenerating && (state.input.isNotBlank() ||
                            tool == DeveloperTool.UUID_GENERATOR),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(tool.actionRes())) }

                    state.inputError?.let { error ->
                        ErrorCard(message = error.toDisplayMessage(context))
                    }

                    if (state.isGenerating && state.output.isBlank()) {
                        LoadingState(label = stringResource(R.string.loading_analyzing))
                    }

                    state.failure?.let { failure ->
                        ErrorCard(
                            message = stringResource(failure.messageRes()),
                            technicalDetail = failure.technicalDetailOrNull(
                                context,
                                verboseErrors,
                            ),
                            actionLabel = if (failure.offersRetry) {
                                stringResource(R.string.action_retry)
                            } else {
                                null
                            },
                            onAction = onRun.takeIf { failure.offersRetry },
                        )
                    }

                    if (state.hasOutput) {
                        ResultCard(
                            text = state.output.toDisplayOutput(context),
                            label = stringResource(R.string.dev_output_label),
                            // Developer output is structured text: alignment matters, so
                            // it is rendered monospaced.
                            textStyle = if (state.isOutputMonospace) MonospaceBody else null,
                            onCopy = { textActions.copy("developer", state.output.toDisplayOutput(context)) },
                            onShare = { textActions.share(state.output.toDisplayOutput(context)) },
                            onSave = onSave,
                            saved = state.savedToHistory,
                            saveEnabled = !state.isGenerating,
                        )
                    }

                    if (state.input.isNotBlank() || state.hasOutput) {
                        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                }
            }

            // Only the two explanation tools need a model; the rest render immediately.
            if (tool.needsAi) {
                GenAiGate(
                    gateState = gateStateOf(capability, downloadState),
                    downloadState = downloadState,
                    onDownload = onDownload,
                    onRetryCheck = onRetryCheck,
                    content = body,
                )
            } else {
                body()
            }

            Spacer(Modifier.height(Spacing.XXXL))
        }
    }
}

@Composable
private fun ToolOptions(
    tool: DeveloperTool,
    state: DeveloperUiState,
    onErrorLanguageChange: (ErrorLanguage) -> Unit,
    onBase64DirectionChange: (Boolean) -> Unit,
    onUrlDirectionChange: (Boolean) -> Unit,
    onHashAlgorithmChange: (DevTools.HashAlgorithm) -> Unit,
    onUuidCountChange: (Int) -> Unit,
) {
    when (tool) {
        DeveloperTool.EXPLAIN_ERROR, DeveloperTool.EXPLAIN_CODE -> {
            OptionGroup(label = stringResource(R.string.dev_language)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    ErrorLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = state.errorLanguage == language,
                            onClick = { onErrorLanguageChange(language) },
                            label = { Text(stringResource(language.labelRes)) },
                        )
                    }
                }
            }
        }

        DeveloperTool.BASE64 -> DirectionChips(
            decode = state.base64Decode,
            onChange = onBase64DirectionChange,
        )

        DeveloperTool.URL_CODEC -> DirectionChips(
            decode = state.urlDecode,
            onChange = onUrlDirectionChange,
        )

        DeveloperTool.HASH_GENERATOR -> OptionGroup(
            label = stringResource(R.string.dev_hash_algorithm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                DevTools.HashAlgorithm.entries.forEach { algorithm ->
                    FilterChip(
                        selected = state.hashAlgorithm == algorithm,
                        onClick = { onHashAlgorithmChange(algorithm) },
                        label = { Text(algorithm.displayName) },
                    )
                }
            }
        }

        DeveloperTool.UUID_GENERATOR -> OptionGroup(
            label = stringResource(R.string.dev_uuid_count),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                listOf(1, 5, 10, 25).forEach { count ->
                    FilterChip(
                        selected = state.uuidCount == count,
                        onClick = { onUuidCountChange(count) },
                        label = { Text(count.toString()) },
                    )
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun DirectionChips(decode: Boolean, onChange: (Boolean) -> Unit) {
    OptionGroup(label = stringResource(R.string.dev_direction)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            FilterChip(
                selected = !decode,
                onClick = { onChange(false) },
                label = { Text(stringResource(R.string.dev_encode)) },
            )
            FilterChip(
                selected = decode,
                onClick = { onChange(true) },
                label = { Text(stringResource(R.string.dev_decode)) },
            )
        }
    }
}

/**
 * Turns the ViewModel's markers into localised text.
 *
 * The ViewModel emits stable markers rather than strings so it stays free of Android
 * resources; the mapping to what the user reads happens here.
 */
private fun String.toDisplayMessage(context: android.content.Context): String = when (this) {
    DeveloperViewModel.INPUT_TOO_LARGE_MARKER -> context.getString(R.string.dev_input_too_large)
    DeveloperViewModel.INVALID_JSON_MARKER -> context.getString(R.string.dev_json_invalid)
    DeveloperViewModel.INVALID_BASE64_MARKER -> context.getString(R.string.dev_base64_invalid)
    DeveloperViewModel.INVALID_URL_MARKER -> context.getString(R.string.dev_url_invalid)
    DeveloperViewModel.INVALID_JWT_MARKER -> context.getString(R.string.dev_jwt_invalid)
    DeveloperViewModel.INVALID_TIMESTAMP_MARKER ->
        context.getString(R.string.dev_timestamp_invalid)
    // A positioned JSON parse error is already a readable sentence.
    else -> this
}

private fun String.toDisplayOutput(context: android.content.Context): String =
    if (startsWith(DeveloperViewModel.VALID_JSON_MARKER)) {
        context.getString(R.string.dev_json_valid) +
            removePrefix(DeveloperViewModel.VALID_JSON_MARKER)
    } else {
        this
    }

private fun DeveloperTool.inputHintRes(): Int = when (this) {
    DeveloperTool.EXPLAIN_ERROR -> R.string.dev_explain_error_hint
    DeveloperTool.EXPLAIN_CODE -> R.string.dev_explain_code_hint
    DeveloperTool.JSON_FORMATTER, DeveloperTool.JSON_VALIDATOR -> R.string.dev_json_hint
    DeveloperTool.BASE64 -> R.string.dev_base64_hint
    DeveloperTool.URL_CODEC -> R.string.dev_url_hint
    DeveloperTool.JWT_DECODER -> R.string.dev_jwt_hint
    DeveloperTool.UUID_GENERATOR -> R.string.dev_uuid_title
    DeveloperTool.HASH_GENERATOR -> R.string.dev_hash_hint
    DeveloperTool.TIMESTAMP -> R.string.dev_timestamp_hint
}

private fun DeveloperTool.actionRes(): Int = when (this) {
    DeveloperTool.EXPLAIN_ERROR, DeveloperTool.EXPLAIN_CODE -> R.string.dev_explain_action
    DeveloperTool.JSON_FORMATTER -> R.string.dev_format_action
    DeveloperTool.JSON_VALIDATOR -> R.string.dev_validate_action
    DeveloperTool.UUID_GENERATOR -> R.string.dev_generate_action
    else -> R.string.dev_run_action
}

/** Tools whose input is code or structured data read better in a monospaced font. */
private fun DeveloperTool.usesMonospaceInput(): Boolean = when (this) {
    DeveloperTool.JSON_FORMATTER,
    DeveloperTool.JSON_VALIDATOR,
    DeveloperTool.EXPLAIN_CODE,
    DeveloperTool.EXPLAIN_ERROR,
    DeveloperTool.JWT_DECODER,
    -> true
    else -> false
}

@Preview(name = "Developer - JSON formatter", showBackground = true)
@Composable
private fun DeveloperJsonPreview() {
    LocalAiTheme {
        DeveloperToolContent(
            tool = DeveloperTool.JSON_FORMATTER,
            state = DeveloperUiState(
                input = """{"a":1,"b":[true,null]}""",
                output = "{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null\n  ]\n}",
            ),
            capability = AiCapability.unknown(AiTask.ASK),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onRun = {},
            onClear = {},
            onSave = {},
            onErrorLanguageChange = {},
            onBase64DirectionChange = {},
            onUrlDirectionChange = {},
            onHashAlgorithmChange = {},
            onUuidCountChange = {},
            onUseNow = {},
            onDownload = {},
            onRetryCheck = {},
            onNavigateUp = {},
        )
    }
}
