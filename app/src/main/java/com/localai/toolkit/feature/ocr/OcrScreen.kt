package com.localai.toolkit.feature.ocr

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.EmptyState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
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
import com.localai.toolkit.domain.model.ToolId
import kotlinx.coroutines.launch

@Composable
fun OcrScreen(
    onNavigateUp: () -> Unit,
    onOpenTool: (ToolId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OcrViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    OcrContent(
        state = state,
        verboseErrors = verboseErrors,
        onImageSelected = viewModel::onImageSelected,
        onRetry = viewModel::onRetry,
        onClear = viewModel::onClear,
        onSave = viewModel::onSave,
        onSendTo = { tool ->
            viewModel.sendTo(tool)
            onOpenTool(tool)
        },
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun OcrContent(
    state: OcrUiState,
    verboseErrors: Boolean,
    onImageSelected: (Uri) -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onSendTo: (ToolId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    // The photo picker grants access to exactly the item the user chose, with no storage
    // permission of any kind.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onImageSelected) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.tool_ocr_title),
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
            if (state.imageUri == null) {
                EmptyState(
                    icon = Icons.Outlined.ImageSearch,
                    title = stringResource(R.string.ocr_empty_title),
                    description = stringResource(R.string.ocr_empty_body),
                    actionLabel = stringResource(R.string.action_choose_photo),
                    onAction = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.height(420.dp),
                )
            } else {
                // Rendered from the already-downsampled preview bitmap rather than an
                // image loading library: the file has been decoded once already, and
                // pulling in a loader for one image would not earn its size.
                state.preview?.let { preview ->
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = stringResource(R.string.ocr_selected_image),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .padding(top = Spacing.M),
                    )
                }

                when {
                    state.isRecognizing -> LoadingState(
                        label = stringResource(R.string.loading_extracting_text),
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
                        onAction = onRetry.takeIf { state.failure.offersRetry },
                    )

                    state.noTextDetected -> ErrorCard(
                        message = stringResource(R.string.error_no_text_detected),
                        actionLabel = stringResource(R.string.action_choose_photo),
                        onAction = {
                            pickImage.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )

                    state.hasResult -> ResultCard(
                        text = state.extractedText,
                        label = stringResource(R.string.ocr_result_label),
                        onCopy = {
                            context.copyToClipboard("ocr", state.extractedText)
                            if (shouldShowCopyConfirmation()) {
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }
                        },
                        onShare = { context.shareText(state.extractedText) },
                        onSave = onSave,
                        saved = state.savedToHistory,
                        // The point of OCR is rarely the text itself - it is what you do
                        // with it next, so the follow-ups are first-class here.
                        secondaryActions = listOf(
                            ResultAction(stringResource(R.string.tool_summarize_title)) {
                                onSendTo(ToolId.SUMMARIZE)
                            },
                            ResultAction(stringResource(R.string.tool_translate_title)) {
                                onSendTo(ToolId.TRANSLATE)
                            },
                            ResultAction(stringResource(R.string.tool_rewrite_title)) {
                                onSendTo(ToolId.REWRITE)
                            },
                            ResultAction(stringResource(R.string.tool_ask_title)) {
                                onSendTo(ToolId.ASK)
                            },
                        ),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    Button(
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ocr_choose_another)) }

                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_clear)) }
                }
            }

            Spacer(Modifier.height(Spacing.XXXL))
        }
    }
}

@Preview(name = "OCR - empty", showBackground = true)
@Composable
private fun OcrEmptyPreview() {
    LocalAiTheme {
        OcrContent(
            state = OcrUiState(),
            verboseErrors = false,
            onImageSelected = {},
            onRetry = {},
            onClear = {},
            onSave = {},
            onSendTo = {},
            onNavigateUp = {},
        )
    }
}
