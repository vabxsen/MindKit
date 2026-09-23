package com.localai.toolkit.feature.summarize

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.engine.SummaryInputType
import com.localai.toolkit.ai.engine.SummaryLength
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
fun SummarizeScreen(
    onNavigateUp: () -> Unit,
    onOpenTool: (ToolId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SummarizeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    SummarizeContent(
        state = state,
        capability = capability,
        downloadState = downloadState,
        verboseErrors = verboseErrors,
        onInputChange = viewModel::onInputChange,
        onLengthChange = viewModel::onLengthChange,
        onInputTypeChange = viewModel::onInputTypeChange,
        onSummarize = viewModel::onSummarize,
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
internal fun SummarizeContent(
    state: SummarizeUiState,
    capability: AiCapability,
    downloadState: ModelDownloadState,
    verboseErrors: Boolean,
    onInputChange: (String) -> Unit,
    onLengthChange: (SummaryLength) -> Unit,
    onInputTypeChange: (SummaryInputType) -> Unit,
    onSummarize: () -> Unit,
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
        title = stringResource(R.string.tool_summarize_title),
        inputHint = stringResource(R.string.summarize_input_hint),
        actionLabel = stringResource(R.string.summarize_action),
        input = state.input,
        onInputChange = onInputChange,
        onAction = onSummarize,
        actionEnabled = state.canSummarize,
        gateState = gateStateOf(capability, downloadState),
        downloadState = downloadState,
        onDownload = onDownload,
        onRetryCheck = onRetryCheck,
        onClear = onClear,
        onNavigateUp = onNavigateUp,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        options = {
            OptionGroup(label = stringResource(R.string.summarize_input_type)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    SummaryInputType.entries.forEach { type ->
                        FilterChip(
                            selected = state.inputType == type,
                            onClick = { onInputTypeChange(type) },
                            label = { Text(stringResource(type.labelRes())) },
                        )
                    }
                }
            }

            OptionGroup(
                label = stringResource(R.string.summarize_length),
                // The API's only output control is the number of bullet points, so the
                // UI says exactly that instead of implying a prose-length setting.
                supportingText = stringResource(R.string.summarize_length_explainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    SummaryLength.entries.forEach { length ->
                        FilterChip(
                            selected = state.length == length,
                            onClick = { onLengthChange(length) },
                            label = { Text(stringResource(length.labelRes())) },
                        )
                    }
                }
            }
        },
        result = {
            when {
                state.isSummarizing -> LoadingState(
                    label = stringResource(R.string.loading_summarizing),
                )

                state.failure != null -> ErrorCard(
                    message = stringResource(state.failure.messageRes()),
                    technicalDetail = state.failure.technicalDetailOrNull(context, verboseErrors),
                    actionLabel = if (state.failure.offersRetry) {
                        stringResource(R.string.action_retry)
                    } else {
                        null
                    },
                    onAction = onSummarize.takeIf { state.failure.offersRetry },
                )

                state.hasResult -> ResultCard(
                    text = state.summary,
                    label = stringResource(R.string.summarize_result_label),
                    onCopy = {
                        context.copyToClipboard("summary", state.summary)
                        if (shouldShowCopyConfirmation()) {
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        }
                    },
                    onShare = { context.shareText(state.summary) },
                    onSave = onSave,
                    saved = state.savedToHistory,
                    secondaryActions = listOf(
                        ResultAction(stringResource(R.string.tool_translate_title)) {
                            onSendTo(ToolId.TRANSLATE)
                        },
                        ResultAction(stringResource(R.string.tool_rewrite_title)) {
                            onSendTo(ToolId.REWRITE)
                        },
                    ),
                )
            }
        },
    )
}

private fun SummaryLength.labelRes(): Int = when (this) {
    SummaryLength.SHORT -> R.string.summarize_length_short
    SummaryLength.MEDIUM -> R.string.summarize_length_medium
    SummaryLength.DETAILED -> R.string.summarize_length_detailed
}

private fun SummaryInputType.labelRes(): Int = when (this) {
    SummaryInputType.ARTICLE -> R.string.summarize_type_article
    SummaryInputType.CONVERSATION -> R.string.summarize_type_conversation
}

@Preview(name = "Summarize - result", showBackground = true)
@Composable
private fun SummarizeResultPreview() {
    LocalAiTheme {
        SummarizeContent(
            state = SummarizeUiState(
                input = "A long article about on-device machine learning.",
                summary = "- Models now run locally\n- Privacy improves as a result",
            ),
            capability = AiCapability(
                AiTask.SUMMARIZE,
                AiCapabilityStatus.AVAILABLE,
                AiProvider.GEMINI_NANO,
            ),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onLengthChange = {},
            onInputTypeChange = {},
            onSummarize = {},
            onSave = {},
            onClear = {},
            onDownload = {},
            onRetryCheck = {},
            onSendTo = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "Summarize - generating", showBackground = true)
@Composable
private fun SummarizeLoadingPreview() {
    LocalAiTheme {
        SummarizeContent(
            state = SummarizeUiState(input = "Some text", isSummarizing = true),
            capability = AiCapability(
                AiTask.SUMMARIZE,
                AiCapabilityStatus.AVAILABLE,
                AiProvider.GEMINI_NANO,
            ),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onLengthChange = {},
            onInputTypeChange = {},
            onSummarize = {},
            onSave = {},
            onClear = {},
            onDownload = {},
            onRetryCheck = {},
            onSendTo = {},
            onNavigateUp = {},
        )
    }
}
