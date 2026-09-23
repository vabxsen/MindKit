package com.localai.toolkit.feature.ask

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.AskRole
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.component.EmptyState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.component.StatusChip
import com.localai.toolkit.core.designsystem.component.StatusTone
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.LocalReducedMotion
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.ui.messageRes
import com.localai.toolkit.core.ui.offersRetry
import com.localai.toolkit.core.ui.technicalDetailOrNull
import com.localai.toolkit.core.util.copyToClipboard
import com.localai.toolkit.core.util.shareText
import com.localai.toolkit.core.util.shouldShowCopyConfirmation
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.feature.common.GateState
import com.localai.toolkit.feature.common.GenAiGate
import com.localai.toolkit.feature.common.gateStateOf
import kotlinx.coroutines.launch

@Composable
fun AskScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    AskContent(
        state = state,
        capability = capability,
        downloadState = downloadState,
        verboseErrors = verboseErrors,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::onSend,
        onStop = viewModel::onStop,
        onRetry = viewModel::onRetry,
        onNewConversation = viewModel::onNewConversation,
        onSave = viewModel::onSave,
        onDownload = viewModel::onDownloadModel,
        onRetryCheck = viewModel::onRetryCapabilityCheck,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun AskContent(
    state: AskUiState,
    capability: AiCapability,
    downloadState: ModelDownloadState,
    verboseErrors: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onNewConversation: () -> Unit,
    onSave: (Long) -> Unit,
    onDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    var showInfo by remember { mutableStateOf(false) }

    val gateState = gateStateOf(capability, downloadState)

    // Follow the newest message as it streams in.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.tool_ask_title),
                onNavigateUp = onNavigateUp,
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.cd_info),
                        )
                    }
                    if (!state.isEmpty) {
                        IconButton(onClick = onNewConversation) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.ask_new_conversation),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // The provenance badge is deliberately always visible while the feature is
            // usable: the user should never have to guess where their prompt went.
            if (gateState == GateState.READY) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.S),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(
                        label = stringResource(R.string.badge_local),
                        tone = StatusTone.Ready,
                        contentDescription = stringResource(R.string.badge_local_description),
                    )
                    Text(
                        text = capability.baseModelName
                            ?: stringResource(R.string.provider_gemini_nano),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                GenAiGate(
                    gateState = gateState,
                    downloadState = downloadState,
                    onDownload = onDownload,
                    onRetryCheck = onRetryCheck,
                ) {
                    if (state.isEmpty) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            title = stringResource(R.string.ask_empty_title),
                            description = stringResource(R.string.ask_empty_body),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = Spacing.ScreenHorizontal,
                                vertical = Spacing.S,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.M),
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                MessageBubble(
                                    message = message,
                                    saved = message.id in state.savedMessageIds,
                                    verboseErrors = verboseErrors,
                                    onCopy = {
                                        context.copyToClipboard("answer", message.text)
                                        if (shouldShowCopyConfirmation()) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(copiedMessage)
                                            }
                                        }
                                    },
                                    onShare = { context.shareText(message.text) },
                                    onSave = { onSave(message.id) },
                                    onRetry = onRetry,
                                )
                            }
                        }
                    }
                }
            }

            if (gateState == GateState.READY) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = Spacing.ScreenHorizontal,
                            vertical = Spacing.S,
                        ),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        placeholder = { Text(stringResource(R.string.ask_input_hint)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 5,
                    )
                    if (state.isGenerating) {
                        IconButton(onClick = onStop) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = stringResource(R.string.ask_stop),
                            )
                        }
                    } else {
                        IconButton(onClick = onSend, enabled = state.canSend) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = stringResource(R.string.action_send),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.ask_info_title)) },
            text = { Text(stringResource(R.string.ask_info_body)) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun MessageBubble(
    message: AskMessage,
    saved: Boolean,
    verboseErrors: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val isUser = message.role == AskRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (message.failure != null) {
            ErrorCard(
                message = stringResource(message.failure.messageRes()),
                technicalDetail = message.failure.technicalDetailOrNull(context, verboseErrors),
                actionLabel = if (message.failure.offersRetry) {
                    stringResource(R.string.action_retry)
                } else {
                    null
                },
                onAction = onRetry.takeIf { message.failure.offersRetry },
            )
            return@Column
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (isUser) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Row(
                modifier = Modifier.padding(Spacing.M),
                verticalAlignment = Alignment.Bottom,
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (message.isStreaming) {
                    StreamingCaret(modifier = Modifier.padding(start = Spacing.XS))
                }
            }
        }

        // Actions only once the answer is complete, so the row does not flicker in and
        // out while text streams.
        if (!isUser && !message.isStreaming && message.text.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.action_share),
                        modifier = Modifier.size(18.dp),
                    )
                }
                TextButton(onClick = onSave, enabled = !saved) {
                    Text(
                        stringResource(if (saved) R.string.action_saved else R.string.action_save),
                    )
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_regenerate))
                }
            }
        }
    }
}

/** A pulsing block that marks text as still being generated. */
@Composable
private fun StreamingCaret(modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    if (reducedMotion) {
        // A static marker still communicates "in progress" without animation.
        Box(
            modifier = modifier
                .size(width = 8.dp, height = 16.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        return
    }

    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        modifier = modifier
            .size(width = 8.dp, height = 16.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Preview(name = "Ask - conversation", showBackground = true)
@Composable
private fun AskConversationPreview() {
    LocalAiTheme {
        AskContent(
            state = AskUiState(
                messages = listOf(
                    AskMessage(0, AskRole.USER, "Explain what a coroutine is."),
                    AskMessage(
                        1,
                        AskRole.MODEL,
                        "A coroutine is a suspendable computation that lets you write " +
                            "asynchronous code in a sequential style.",
                    ),
                ),
            ),
            capability = AiCapability(
                task = AiTask.ASK,
                status = com.localai.toolkit.domain.model.AiCapabilityStatus.AVAILABLE,
                provider = com.localai.toolkit.domain.model.AiProvider.GEMINI_NANO,
                baseModelName = "gemini-nano",
            ),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onNewConversation = {},
            onSave = {},
            onDownload = {},
            onRetryCheck = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "Ask - unsupported device", showBackground = true)
@Composable
private fun AskUnsupportedPreview() {
    LocalAiTheme {
        AskContent(
            state = AskUiState(),
            capability = AiCapability.unsupported(AiTask.ASK),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onNewConversation = {},
            onSave = {},
            onDownload = {},
            onRetryCheck = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "Ask - model download required", showBackground = true)
@Composable
private fun AskDownloadPreview() {
    LocalAiTheme {
        AskContent(
            state = AskUiState(),
            capability = AiCapability(
                task = AiTask.ASK,
                status = com.localai.toolkit.domain.model.AiCapabilityStatus.DOWNLOADABLE,
                provider = com.localai.toolkit.domain.model.AiProvider.GEMINI_NANO,
            ),
            downloadState = ModelDownloadState.Idle,
            verboseErrors = false,
            onInputChange = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onNewConversation = {},
            onSave = {},
            onDownload = {},
            onRetryCheck = {},
            onNavigateUp = {},
        )
    }
}
