package com.localai.toolkit.feature.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.localai.toolkit.ai.engine.RewriteStyle
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
fun RewriteScreen(
    onNavigateUp: () -> Unit,
    onOpenTool: (ToolId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RewriteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    RewriteContent(
        state = state,
        capability = capability,
        downloadState = downloadState,
        verboseErrors = verboseErrors,
        onInputChange = viewModel::onInputChange,
        onStyleChange = viewModel::onStyleChange,
        onRewrite = viewModel::onRewrite,
        onAccept = viewModel::onAccept,
        onToggleComparison = viewModel::onToggleComparison,
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
internal fun RewriteContent(
    state: RewriteUiState,
    capability: AiCapability,
    downloadState: ModelDownloadState,
    verboseErrors: Boolean,
    onInputChange: (String) -> Unit,
    onStyleChange: (RewriteStyle) -> Unit,
    onRewrite: () -> Unit,
    onAccept: () -> Unit,
    onToggleComparison: () -> Unit,
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
    val acceptedMessage = stringResource(R.string.rewrite_accepted)

    TextToolScaffold(
        title = stringResource(R.string.tool_rewrite_title),
        inputHint = stringResource(R.string.rewrite_input_hint),
        actionLabel = stringResource(R.string.rewrite_action),
        input = state.input,
        onInputChange = onInputChange,
        onAction = onRewrite,
        actionEnabled = state.canRewrite,
        gateState = gateStateOf(capability, downloadState),
        downloadState = downloadState,
        onDownload = onDownload,
        onRetryCheck = onRetryCheck,
        onClear = onClear,
        onNavigateUp = onNavigateUp,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        options = {
            OptionGroup(label = stringResource(R.string.rewrite_style)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    RewriteStyle.entries.forEach { style ->
                        FilterChip(
                            selected = state.style == style,
                            onClick = { onStyleChange(style) },
                            label = { Text(stringResource(style.labelRes())) },
                        )
                    }
                }
            }
        },
        result = {
            when {
                state.isRewriting -> LoadingState(
                    label = stringResource(R.string.loading_rewriting),
                )

                state.failure != null -> ErrorCard(
                    message = stringResource(state.failure.messageRes()),
                    technicalDetail = state.failure.technicalDetailOrNull(context, verboseErrors),
                    actionLabel = if (state.failure.offersRetry) {
                        stringResource(R.string.action_retry)
                    } else {
                        null
                    },
                    onAction = onRewrite.takeIf { state.failure.offersRetry },
                )

                state.hasResult -> Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    ResultCard(
                        text = state.rewritten,
                        label = stringResource(R.string.rewrite_result_label),
                        onCopy = {
                            context.copyToClipboard("rewrite", state.rewritten)
                            if (shouldShowCopyConfirmation()) {
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }
                        },
                        onShare = { context.shareText(state.rewritten) },
                        onSave = onSave,
                        saved = state.savedToHistory,
                        secondaryActions = listOf(
                            ResultAction(stringResource(R.string.tool_proofread_title)) {
                                onSendTo(ToolId.PROOFREAD)
                            },
                            ResultAction(stringResource(R.string.tool_translate_title)) {
                                onSendTo(ToolId.TRANSLATE)
                            },
                        ),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        OutlinedButton(
                            onClick = {
                                onAccept()
                                scope.launch { snackbarHostState.showSnackbar(acceptedMessage) }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.rewrite_accept)) }

                        TextButton(onClick = onRewrite) {
                            Text(stringResource(R.string.action_regenerate))
                        }
                    }

                    TextButton(onClick = onToggleComparison) {
                        Text(
                            stringResource(
                                if (state.showOriginal) {
                                    R.string.rewrite_hide_original
                                } else {
                                    R.string.rewrite_show_original
                                },
                            ),
                        )
                    }

                    AnimatedVisibility(visible = state.showOriginal) {
                        ResultCard(
                            text = state.comparedOriginal,
                            label = stringResource(R.string.rewrite_original_label),
                            onCopy = {
                                context.copyToClipboard("original", state.comparedOriginal)
                            },
                            onShare = { context.shareText(state.comparedOriginal) },
                        )
                    }
                }
            }
        },
    )
}

private fun RewriteStyle.labelRes(): Int = when (this) {
    RewriteStyle.REPHRASE -> R.string.rewrite_style_rephrase
    RewriteStyle.PROFESSIONAL -> R.string.rewrite_style_professional
    RewriteStyle.FRIENDLY -> R.string.rewrite_style_friendly
    RewriteStyle.SHORTEN -> R.string.rewrite_style_shorten
    RewriteStyle.ELABORATE -> R.string.rewrite_style_elaborate
    RewriteStyle.EMOJIFY -> R.string.rewrite_style_emojify
}

@Preview(name = "Rewrite - result", showBackground = true)
@Composable
private fun RewritePreview() {
    LocalAiTheme {
        RewriteContent(
            state = RewriteUiState(
                input = "hey can you send me that file when you get a sec",
                rewritten = "Could you please send me that file when you have a moment?",
                comparedOriginal = "hey can you send me that file when you get a sec",
                style = RewriteStyle.PROFESSIONAL,
            ),
            capability = AiCapability(
                AiTask.REWRITE,
                AiCapabilityStatus.AVAILABLE,
                AiProvider.GEMINI_NANO,
            ),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onStyleChange = {},
            onRewrite = {},
            onAccept = {},
            onToggleComparison = {},
            onSave = {},
            onClear = {},
            onDownload = {},
            onRetryCheck = {},
            onSendTo = {},
            onNavigateUp = {},
        )
    }
}
