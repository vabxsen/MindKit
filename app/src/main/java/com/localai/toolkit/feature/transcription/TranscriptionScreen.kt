package com.localai.toolkit.feature.transcription

import com.localai.toolkit.feature.common.HistorySaveFeedback
import com.localai.toolkit.feature.common.ObserveHistorySaveFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.gemini.TranscriptionMode
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
import com.localai.toolkit.core.ui.rememberTextActionHandler
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.feature.capability.labelRes

@Composable
fun TranscriptionScreen(
    onNavigateUp: () -> Unit,
    onOpenTool: (ToolId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TranscriptionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.onScreenHidden() }
    }

    TranscriptionContent(
        state = state,
        verboseErrors = verboseErrors,
        saveFeedback = viewModel.saveFeedback,
        onModeChange = viewModel::onModeChange,
        onStartRecording = viewModel::onStartRecording,
        onStopRecording = viewModel::onStopRecording,
        onFileSelected = viewModel::onFileSelected,
        onMicrophoneDenied = viewModel::onMicrophoneDenied,
        onDownload = viewModel::onDownloadModel,
        onRetryCheck = viewModel::onRetry,
        onSave = viewModel::onSave,
        onClear = viewModel::onClear,
        onSendTo = { tool ->
            viewModel.sendTo(tool)
            onOpenTool(tool)
        },
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun TranscriptionContent(
    state: TranscriptionUiState,
    verboseErrors: Boolean,
    onModeChange: (TranscriptionMode) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onFileSelected: (android.net.Uri) -> Unit,
    onMicrophoneDenied: () -> Unit,
    onDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onSendTo: (ToolId) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    saveFeedback: Flow<HistorySaveFeedback> = emptyFlow(),
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    ObserveHistorySaveFeedback(saveFeedback, snackbarHostState)
    val textActions = rememberTextActionHandler(snackbarHostState)

    // Requested at the moment the user taps record, never on screen entry.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartRecording() else onMicrophoneDenied() }

    val pickAudio = rememberLauncherForActivityResult(
        // The Storage Access Framework grants access to exactly this one file.
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onFileSelected) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.tool_transcribe_title),
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
            // Both modes are always listed, with their real status, so the user can see
            // that advanced transcription exists even where it is not supported.
            Row(
                modifier = Modifier.padding(top = Spacing.M),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                TranscriptionMode.entries.forEach { mode ->
                    val status = if (mode == TranscriptionMode.ADVANCED) {
                        state.advancedStatus
                    } else {
                        state.basicStatus
                    }
                    FilterChip(
                        selected = state.mode == mode,
                        enabled = status != AiCapabilityStatus.UNSUPPORTED && !state.isBusy,
                        onClick = { onModeChange(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }

            StatusChip(
                label = stringResource(state.currentStatus.labelRes()),
                tone = when (state.currentStatus) {
                    AiCapabilityStatus.AVAILABLE -> StatusTone.Ready
                    AiCapabilityStatus.DOWNLOADABLE,
                    AiCapabilityStatus.DOWNLOADING,
                    -> StatusTone.Pending
                    AiCapabilityStatus.UNKNOWN -> StatusTone.Neutral
                    else -> StatusTone.Unsupported
                },
                contentDescription = stringResource(state.provider.labelRes()),
            )

            when {
                // Availability/download updates must never take away the escape
                // action for a recognition operation that already owns the screen.
                state.isRecording -> {
                    LoadingState(label = stringResource(R.string.transcribe_listening))
                    Button(onClick = onStopRecording, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.transcribe_stop))
                    }
                }

                state.isTranscribingFile -> {
                    LoadingState(label = stringResource(R.string.loading_transcribing))
                    Text(
                        text = stringResource(R.string.transcribe_file_pacing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                state.downloadState is ModelDownloadState.Started ||
                    state.downloadState is ModelDownloadState.InProgress -> {
                    LoadingState(
                        label = stringResource(R.string.loading_downloading_model),
                        progress = (state.downloadState as? ModelDownloadState.InProgress)?.fraction,
                    )
                    OutlinedButton(onClick = onRetryCheck, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_check_status))
                    }
                }

                state.currentStatus == AiCapabilityStatus.ERROR ||
                    state.currentStatus == AiCapabilityStatus.TEMPORARILY_UNAVAILABLE -> ErrorCard(
                    message = stringResource(R.string.gate_check_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetryCheck,
                )

                state.currentStatus == AiCapabilityStatus.DOWNLOADING &&
                    state.downloadState !is ModelDownloadState.Failed -> {
                    LoadingState(label = stringResource(R.string.loading_downloading_model))
                    OutlinedButton(onClick = onRetryCheck, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_check_status))
                    }
                }

                state.currentStatus == AiCapabilityStatus.UNKNOWN -> LoadingState(
                    label = stringResource(R.string.loading_checking_availability),
                )

                state.currentStatus == AiCapabilityStatus.UNSUPPORTED -> EmptyState(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.gate_unsupported_title),
                    description = stringResource(R.string.transcribe_unsupported_body),
                )

                state.currentStatus == AiCapabilityStatus.DOWNLOADABLE ||
                    state.downloadState is ModelDownloadState.Failed -> {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                        Text(
                            text = stringResource(R.string.gate_download_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_download_model))
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_record)) }

                    if (state.supportsFileInput) {
                        OutlinedButton(
                            onClick = { pickAudio.launch(arrayOf("audio/*")) },
                            enabled = state.canStart,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_choose_audio)) }
                    } else {
                        // Said plainly rather than hiding the option with no reason.
                        Text(
                            text = stringResource(R.string.transcribe_no_file_support),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.failure?.let { failure ->
                ErrorCard(
                    message = stringResource(failure.messageRes()),
                    technicalDetail = failure.technicalDetailOrNull(context, verboseErrors),
                    actionLabel = if (failure.offersRetry && !state.isBusy) {
                        stringResource(R.string.action_retry)
                    } else {
                        null
                    },
                    onAction = onRetryCheck.takeIf { failure.offersRetry && !state.isBusy },
                )
            }

            if (state.failure == null && state.downloadCheckFailure != null) {
                ErrorCard(
                    message = stringResource(R.string.gate_check_failed),
                    technicalDetail = state.downloadCheckFailure.technicalDetailOrNull(context, verboseErrors),
                    actionLabel = if (!state.isBusy && !state.isCheckingDownload) {
                        stringResource(R.string.action_check_status)
                    } else null,
                    onAction = onRetryCheck.takeIf { !state.isBusy && !state.isCheckingDownload },
                )
            }

            if (state.hasTranscript) {
                ResultCard(
                    text = state.transcript,
                    label = stringResource(R.string.transcribe_result_label),
                    onCopy = { textActions.copy("transcript", state.transcript) },
                    onShare = { textActions.share(state.transcript) },
                    onSave = onSave,
                    saved = state.savedToHistory,
                    saveEnabled = !state.isBusy,
                    secondaryActions = listOf(
                        ResultAction(stringResource(R.string.tool_summarize_title)) {
                            onSendTo(ToolId.SUMMARIZE)
                        },
                        ResultAction(stringResource(R.string.tool_rewrite_title)) {
                            onSendTo(ToolId.REWRITE)
                        },
                        ResultAction(stringResource(R.string.tool_translate_title)) {
                            onSendTo(ToolId.TRANSLATE)
                        },
                    ),
                )
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_clear))
                }
            } else if (!state.isBusy && state.currentStatus == AiCapabilityStatus.AVAILABLE) {
                EmptyState(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.transcribe_empty_title),
                    description = stringResource(R.string.transcribe_empty_body),
                )
            }

            Spacer(Modifier.height(Spacing.XXXL))
        }
    }
}

private fun TranscriptionMode.labelRes(): Int = when (this) {
    TranscriptionMode.BASIC -> R.string.transcribe_mode_basic
    TranscriptionMode.ADVANCED -> R.string.transcribe_mode_advanced
}

@Preview(name = "Transcribe - ready", showBackground = true)
@Composable
private fun TranscriptionPreview() {
    LocalAiTheme {
        TranscriptionContent(
            state = TranscriptionUiState(
                basicStatus = AiCapabilityStatus.AVAILABLE,
                advancedStatus = AiCapabilityStatus.UNSUPPORTED,
                provider = AiProvider.ANDROID_PLATFORM,
            ),
            verboseErrors = false,
            onModeChange = {},
            onStartRecording = {},
            onStopRecording = {},
            onFileSelected = {},
            onMicrophoneDenied = {},
            onDownload = {},
            onRetryCheck = {},
            onSave = {},
            onClear = {},
            onSendTo = {},
            onNavigateUp = {},
        )
    }
}
