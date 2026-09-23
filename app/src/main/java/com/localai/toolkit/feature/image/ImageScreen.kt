package com.localai.toolkit.feature.image

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.component.EmptyState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.component.ResultAction
import com.localai.toolkit.core.designsystem.component.ResultCard
import com.localai.toolkit.core.designsystem.component.StatusChip
import com.localai.toolkit.core.designsystem.component.StatusTone
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.ui.messageRes
import com.localai.toolkit.core.ui.offersRetry
import com.localai.toolkit.core.ui.technicalDetailOrNull
import com.localai.toolkit.core.util.copyToClipboard
import com.localai.toolkit.core.util.shareText
import com.localai.toolkit.core.util.shouldShowCopyConfirmation
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.feature.common.GenAiGate
import com.localai.toolkit.feature.common.gateStateOf
import kotlinx.coroutines.launch

/** Ready-made questions, so the tool is useful without the user inventing a prompt. */
private val suggestedPrompts = listOf(
    R.string.image_prompt_whats_in_this,
    R.string.image_prompt_explain_screenshot,
    R.string.image_prompt_what_error,
    R.string.image_prompt_describe_ui,
    R.string.image_prompt_summarize_diagram,
)

@Composable
fun ImageScreen(
    onNavigateUp: () -> Unit,
    onOpenTool: (ToolId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val describeCapability by viewModel.describeCapability.collectAsStateWithLifecycle()
    val askCapability by viewModel.askCapability.collectAsStateWithLifecycle()
    val describeDownload by viewModel.describeDownloadState.collectAsStateWithLifecycle()
    val askDownload by viewModel.askDownloadState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    ImageContent(
        state = state,
        capability = if (state.mode == ImageMode.DESCRIBE) describeCapability else askCapability,
        downloadState = if (state.mode == ImageMode.DESCRIBE) describeDownload else askDownload,
        verboseErrors = verboseErrors,
        onImageSelected = viewModel::onImageSelected,
        onModeChange = viewModel::onModeChange,
        onQuestionChange = viewModel::onQuestionChange,
        onRun = viewModel::onRun,
        onSave = viewModel::onSave,
        onClear = viewModel::onClear,
        onDownload = viewModel::onDownloadModel,
        onRetryCheck = viewModel::onRetryCapabilityCheck,
        onRunOcr = {
            viewModel.sendImageToOcr()
            onOpenTool(ToolId.OCR)
        },
        onSendTextTo = { tool ->
            viewModel.sendTextTo(tool)
            onOpenTool(tool)
        },
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun ImageContent(
    state: ImageUiState,
    capability: AiCapability,
    downloadState: ModelDownloadState,
    verboseErrors: Boolean,
    onImageSelected: (Uri) -> Unit,
    onModeChange: (ImageMode) -> Unit,
    onQuestionChange: (String) -> Unit,
    onRun: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onRunOcr: () -> Unit,
    onSendTextTo: (ToolId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onImageSelected) }

    fun launchPicker() = pickImage.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.tool_image_title),
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
            // Mode selection sits outside the gate: the user should be able to switch to
            // the mode their device does support, rather than being blocked by the other.
            Row(
                modifier = Modifier.padding(top = Spacing.M),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                ImageMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }

            StatusChip(
                label = stringResource(R.string.badge_local),
                tone = StatusTone.Ready,
                contentDescription = stringResource(R.string.image_local_note),
            )

            if (state.preview == null) {
                EmptyState(
                    icon = Icons.Outlined.Image,
                    title = stringResource(R.string.image_empty_title),
                    description = stringResource(R.string.image_empty_body),
                    actionLabel = stringResource(R.string.action_choose_photo),
                    onAction = ::launchPicker,
                    modifier = Modifier.height(360.dp),
                )
                state.failure?.let { failure ->
                    ErrorCard(message = stringResource(failure.messageRes()))
                }
            } else {
                Image(
                    bitmap = state.preview.asImageBitmap(),
                    contentDescription = stringResource(R.string.image_selected),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                )

                GenAiGate(
                    gateState = gateStateOf(capability, downloadState),
                    downloadState = downloadState,
                    onDownload = onDownload,
                    onRetryCheck = onRetryCheck,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                        if (state.mode == ImageMode.ASK) {
                            OutlinedTextField(
                                value = state.question,
                                onValueChange = onQuestionChange,
                                label = { Text(stringResource(R.string.image_question_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                            ) {
                                suggestedPrompts.forEach { promptRes ->
                                    val prompt = stringResource(promptRes)
                                    AssistChip(
                                        onClick = { onQuestionChange(prompt) },
                                        label = { Text(prompt) },
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = onRun,
                            enabled = state.canRun,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (state.mode == ImageMode.DESCRIBE) {
                                        R.string.image_describe_action
                                    } else {
                                        R.string.image_ask_action
                                    },
                                ),
                            )
                        }

                        when {
                            state.isWorking && state.output.isBlank() -> LoadingState(
                                label = stringResource(R.string.loading_describing_image),
                            )

                            state.failure != null -> ErrorCard(
                                message = stringResource(state.failure.messageRes()),
                                technicalDetail = state.failure.technicalDetailOrNull(
                                    context,
                                    verboseErrors,
                                ),
                                actionLabel = if (state.failure.offersRetry) {
                                    stringResource(R.string.action_retry)
                                } else {
                                    null
                                },
                                onAction = onRun.takeIf { state.failure.offersRetry },
                            )

                            state.hasResult -> ResultCard(
                                text = state.output,
                                label = stringResource(
                                    if (state.mode == ImageMode.DESCRIBE) {
                                        R.string.image_description_label
                                    } else {
                                        R.string.image_answer_label
                                    },
                                ),
                                onCopy = {
                                    context.copyToClipboard("image", state.output)
                                    if (shouldShowCopyConfirmation()) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(copiedMessage)
                                        }
                                    }
                                },
                                onShare = { context.shareText(state.output) },
                                onSave = onSave,
                                saved = state.savedToHistory,
                                secondaryActions = listOf(
                                    ResultAction(stringResource(R.string.tool_summarize_title)) {
                                        onSendTextTo(ToolId.SUMMARIZE)
                                    },
                                    ResultAction(stringResource(R.string.tool_translate_title)) {
                                        onSendTextTo(ToolId.TRANSLATE)
                                    },
                                ),
                            )
                        }
                    }
                }

                // Text extraction does not depend on Gemini Nano, so it stays available
                // even when the GenAI gate above is closed.
                OutlinedButton(onClick = onRunOcr, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.image_run_ocr))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    OutlinedButton(
                        onClick = ::launchPicker,
                        modifier = Modifier.fillMaxWidth(0.5f),
                    ) { Text(stringResource(R.string.ocr_choose_another)) }
                    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.XXXL))
        }
    }
}

private fun ImageMode.labelRes(): Int = when (this) {
    ImageMode.DESCRIBE -> R.string.image_mode_describe
    ImageMode.ASK -> R.string.image_mode_ask
}

@Preview(name = "Image AI - empty", showBackground = true)
@Composable
private fun ImageEmptyPreview() {
    LocalAiTheme {
        ImageContent(
            state = ImageUiState(),
            capability = AiCapability.unknown(AiTask.IMAGE_DESCRIPTION),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onImageSelected = {},
            onModeChange = {},
            onQuestionChange = {},
            onRun = {},
            onSave = {},
            onClear = {},
            onDownload = {},
            onRetryCheck = {},
            onRunOcr = {},
            onSendTextTo = {},
            onNavigateUp = {},
        )
    }
}
