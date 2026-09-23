package com.localai.toolkit.feature.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
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
import kotlinx.coroutines.launch

@Composable
fun TranslateScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TranslateViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val verboseErrors by viewModel.verboseErrors.collectAsStateWithLifecycle()

    TranslateContent(
        state = state,
        verboseErrors = verboseErrors,
        onInputChange = viewModel::onInputChange,
        onSourceChange = viewModel::onSourceChange,
        onTargetChange = viewModel::onTargetChange,
        onSwap = viewModel::onSwapLanguages,
        onTranslate = viewModel::onTranslate,
        onDownloadLanguage = viewModel::onDownloadLanguage,
        onAcceptDetected = viewModel::onAcceptDetectedLanguage,
        onSave = viewModel::onSave,
        onClear = viewModel::onClear,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun TranslateContent(
    state: TranslateUiState,
    verboseErrors: Boolean,
    onInputChange: (String) -> Unit,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSwap: () -> Unit,
    onTranslate: () -> Unit,
    onDownloadLanguage: (String) -> Unit,
    onAcceptDetected: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    var pickerFor by remember { mutableStateOf<LanguageSlot?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.tool_translate_title),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.M),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                LanguageButton(
                    label = stringResource(R.string.translate_source),
                    value = state.displayNameOf(state.sourceCode),
                    downloaded = state.sourceCode in state.downloadedLanguages,
                    onClick = { pickerFor = LanguageSlot.SOURCE },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSwap) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = stringResource(R.string.action_swap),
                    )
                }
                LanguageButton(
                    label = stringResource(R.string.translate_target),
                    value = state.displayNameOf(state.targetCode),
                    downloaded = state.targetCode in state.downloadedLanguages,
                    onClick = { pickerFor = LanguageSlot.TARGET },
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.detectedSourceCode != null) {
                AssistChip(
                    onClick = onAcceptDetected,
                    label = {
                        Text(
                            stringResource(
                                R.string.translate_detected,
                                state.displayNameOf(state.detectedSourceCode),
                            ),
                        )
                    },
                )
            }

            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.translate_input_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
            )

            if (state.sameLanguageSelected) {
                Text(
                    text = stringResource(R.string.translate_same_language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Missing language packs are stated up front rather than as a failure after
            // the user presses Translate.
            state.missingModels.forEach { code ->
                val name = state.displayNameOf(code)
                if (state.downloadingCode == code) {
                    LoadingState(
                        label = stringResource(R.string.translate_downloading_language, name),
                    )
                } else {
                    ErrorCard(
                        message = stringResource(R.string.translate_model_needed_body, name),
                        actionLabel = stringResource(R.string.translate_download_language, name),
                        onAction = { onDownloadLanguage(code) },
                    )
                }
            }

            Button(
                onClick = onTranslate,
                enabled = state.canTranslate && state.missingModels.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.translate_action)) }

            when {
                state.isTranslating -> LoadingState(
                    label = stringResource(R.string.loading_translating),
                )

                state.failure != null -> ErrorCard(
                    message = stringResource(state.failure.messageRes()),
                    technicalDetail = state.failure.technicalDetailOrNull(context, verboseErrors),
                    actionLabel = if (state.failure.offersRetry) {
                        stringResource(R.string.action_retry)
                    } else {
                        null
                    },
                    onAction = onTranslate.takeIf { state.failure.offersRetry },
                )

                state.hasResult -> ResultCard(
                    text = state.output,
                    label = stringResource(R.string.translate_result_label),
                    onCopy = {
                        context.copyToClipboard("translation", state.output)
                        if (shouldShowCopyConfirmation()) {
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        }
                    },
                    onShare = { context.shareText(state.output) },
                    onSave = onSave,
                    saved = state.savedToHistory,
                )
            }

            if (state.input.isNotBlank() || state.hasResult) {
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_clear))
                }
            }

            Spacer(Modifier.height(Spacing.XXXL))
        }
    }

    pickerFor?.let { slot ->
        LanguagePickerDialog(
            languages = state.languages,
            downloaded = state.downloadedLanguages,
            selected = if (slot == LanguageSlot.SOURCE) state.sourceCode else state.targetCode,
            onSelect = { code ->
                if (slot == LanguageSlot.SOURCE) onSourceChange(code) else onTargetChange(code)
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }
}

private enum class LanguageSlot { SOURCE, TARGET }

@Composable
private fun LanguageButton(
    label: String,
    value: String,
    downloaded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
            StatusChip(
                label = stringResource(
                    if (downloaded) R.string.models_downloaded else R.string.models_not_downloaded,
                ),
                tone = if (downloaded) StatusTone.Ready else StatusTone.Pending,
                modifier = Modifier.padding(top = Spacing.XXS),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerDialog(
    languages: List<TranslationLanguage>,
    downloaded: Set<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(languages, query) {
        if (query.isBlank()) {
            languages
        } else {
            languages.filter { it.displayName.contains(query, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_pick_language)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.models_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.heightIn(max = 380.dp)) {
                    LazyColumn(modifier = Modifier.padding(top = Spacing.S)) {
                        items(filtered, key = { it.code }) { language ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Spacing.MinTouchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { onSelect(language.code) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = language.displayName,
                                        modifier = Modifier.weight(1f),
                                        color = if (language.code == selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                                if (language.code in downloaded) {
                                    StatusChip(
                                        label = stringResource(R.string.models_downloaded),
                                        tone = StatusTone.Ready,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Preview(name = "Translate", showBackground = true)
@Composable
private fun TranslatePreview() {
    LocalAiTheme {
        TranslateContent(
            state = TranslateUiState(
                languages = listOf(
                    TranslationLanguage("en", "English"),
                    TranslationLanguage("es", "Spanish"),
                ),
                downloadedLanguages = setOf("en"),
                input = "Good morning, how are you?",
            ),
            verboseErrors = false,
            onInputChange = {},
            onSourceChange = {},
            onTargetChange = {},
            onSwap = {},
            onTranslate = {},
            onDownloadLanguage = {},
            onAcceptDetected = {},
            onSave = {},
            onClear = {},
            onNavigateUp = {},
        )
    }
}
