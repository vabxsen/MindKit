package com.localai.toolkit.feature.proofread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.engine.ProofreadInputType
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.component.ResultAction
import com.localai.toolkit.core.designsystem.component.ResultCard
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.ui.messageRes
import com.localai.toolkit.core.ui.offersRetry
import com.localai.toolkit.core.ui.technicalDetailOrNull
import com.localai.toolkit.core.util.copyToClipboard
import com.localai.toolkit.core.util.shareText
import com.localai.toolkit.core.util.shouldShowCopyConfirmation
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.feature.common.OptionGroup
import com.localai.toolkit.feature.common.TextToolScaffold
import com.localai.toolkit.feature.common.gateStateOf
import kotlinx.coroutines.launch

@Composable
fun ProofreadScreen(
    onNavigateUp: () -> Unit,
    onOpenTool: (ToolId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProofreadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    ProofreadContent(
        state = state,
        capability = capability,
        downloadState = downloadState,
        verboseErrors = verboseErrors,
        onInputChange = viewModel::onInputChange,
        onInputTypeChange = viewModel::onInputTypeChange,
        onProofread = viewModel::onProofread,
        onApply = viewModel::onApply,
        onSave = viewModel::onSave,
        onClear = viewModel::onClear,
        onDownload = viewModel::onDownloadModel,
        onRetryCheck = viewModel::onRetryCapabilityCheck,
        onSendTo = { tool ->
            viewModel.sendTo(tool)
            onOpenTool(tool)
        },
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun ProofreadContent(
    state: ProofreadUiState,
    capability: AiCapability,
    downloadState: ModelDownloadState,
    verboseErrors: Boolean,
    onInputChange: (String) -> Unit,
    onInputTypeChange: (ProofreadInputType) -> Unit,
    onProofread: () -> Unit,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onSendTo: (ToolId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    TextToolScaffold(
        title = stringResource(R.string.tool_proofread_title),
        inputHint = stringResource(R.string.proofread_input_hint),
        actionLabel = stringResource(R.string.proofread_action),
        input = state.input,
        onInputChange = onInputChange,
        onAction = onProofread,
        actionEnabled = state.canProofread,
        gateState = gateStateOf(capability, downloadState),
        downloadState = downloadState,
        onDownload = onDownload,
        onRetryCheck = onRetryCheck,
        onClear = onClear,
        onNavigateUp = onNavigateUp,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        options = {
            // The correction model treats dictated text differently from typed text, so
            // this is a real API option rather than decoration.
            OptionGroup(label = stringResource(R.string.proofread_input_source)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    ProofreadInputType.entries.forEach { type ->
                        FilterChip(
                            selected = state.inputType == type,
                            onClick = { onInputTypeChange(type) },
                            label = { Text(stringResource(type.labelRes())) },
                        )
                    }
                }
            }
        },
        result = {
            when {
                state.isProofreading -> LoadingState(
                    label = stringResource(R.string.loading_proofreading),
                )

                state.failure != null -> ErrorCard(
                    message = stringResource(state.failure.messageRes()),
                    technicalDetail = state.failure.technicalDetailOrNull(context, verboseErrors),
                    actionLabel = if (state.failure.offersRetry) {
                        stringResource(R.string.action_retry)
                    } else {
                        null
                    },
                    onAction = onProofread.takeIf { state.failure.offersRetry },
                )

                state.noChangesSuggested -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = stringResource(R.string.proofread_no_changes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.L),
                    )
                }

                state.hasResult -> Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    // Original and suggestion are shown together rather than one
                    // replacing the other, so the user can see what changed before
                    // deciding.
                    ResultCard(
                        text = state.comparedOriginal,
                        label = stringResource(R.string.proofread_original_label),
                        onCopy = { context.copyToClipboard("original", state.comparedOriginal) },
                        onShare = { context.shareText(state.comparedOriginal) },
                    )

                    ResultCard(
                        text = state.corrected,
                        label = stringResource(R.string.proofread_result_label),
                        onCopy = {
                            context.copyToClipboard("proofread", state.corrected)
                            if (shouldShowCopyConfirmation()) {
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }
                        },
                        onShare = { context.shareText(state.corrected) },
                        onSave = onSave,
                        saved = state.savedToHistory,
                        secondaryActions = listOf(
                            ResultAction(stringResource(R.string.tool_rewrite_title)) {
                                onSendTo(ToolId.REWRITE)
                            },
                            ResultAction(stringResource(R.string.tool_translate_title)) {
                                onSendTo(ToolId.TRANSLATE)
                            },
                        ),
                    )

                    OutlinedButton(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.proofread_apply))
                    }
                }
            }
        },
    )
}

private fun ProofreadInputType.labelRes(): Int = when (this) {
    ProofreadInputType.KEYBOARD -> R.string.proofread_source_keyboard
    ProofreadInputType.VOICE -> R.string.proofread_source_voice
}

@Preview(name = "Proofread - suggestion", showBackground = true)
@Composable
private fun ProofreadPreview() {
    LocalAiTheme {
        ProofreadContent(
            state = ProofreadUiState(
                input = "their going to the meeting tommorow",
                corrected = "They are going to the meeting tomorrow.",
                comparedOriginal = "their going to the meeting tommorow",
            ),
            capability = AiCapability(
                AiTask.PROOFREAD,
                AiCapabilityStatus.AVAILABLE,
                AiProvider.GEMINI_NANO,
            ),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onInputTypeChange = {},
            onProofread = {},
            onApply = {},
            onSave = {},
            onClear = {},
            onDownload = {},
            onRetryCheck = {},
            onSendTo = {},
            onNavigateUp = {},
        )
    }
}
