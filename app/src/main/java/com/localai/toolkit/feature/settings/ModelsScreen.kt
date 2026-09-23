package com.localai.toolkit.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.component.StatusChip
import com.localai.toolkit.core.designsystem.component.StatusTone
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.ui.messageRes

@Composable
fun ModelsScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ModelsContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onDownload = viewModel::onDownload,
        onDelete = viewModel::onDelete,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun ModelsContent(
    state: ModelsUiState,
    onQueryChange: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.models_title),
                onNavigateUp = onNavigateUp,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.Huge),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = Spacing.ScreenHorizontal)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.models_translation_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = Spacing.M, bottom = Spacing.S)
                                .semantics { heading() },
                        )
                        Text(
                            text = stringResource(
                                R.string.models_downloaded_count,
                                state.downloaded.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = stringResource(R.string.models_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.M),
                    )

                    state.failure?.let { failure ->
                        ErrorCard(
                            message = stringResource(failure.messageRes()),
                            modifier = Modifier.padding(bottom = Spacing.M),
                        )
                    }

                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        label = { Text(stringResource(R.string.models_search_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.S),
                    )
                }
            }

            items(state.visibleLanguages, key = { it.code }) { language ->
                LanguageRow(
                    language = language,
                    downloaded = language.code in state.downloaded,
                    busy = state.busyCode == language.code,
                    enabled = state.busyCode == null,
                    onDownload = { onDownload(language.code) },
                    onDelete = { onDelete(language.code) },
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = Spacing.ScreenHorizontal)
                        .padding(top = Spacing.XXL),
                    verticalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    Text(
                        text = stringResource(R.string.models_genai_header),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.models_genai_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    language: TranslationLanguage,
    downloaded: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.MinTouchTarget)
            .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StatusChip(
                label = stringResource(
                    if (downloaded) R.string.models_downloaded else R.string.models_not_downloaded,
                ),
                tone = if (downloaded) StatusTone.Ready else StatusTone.Neutral,
                modifier = Modifier.padding(top = Spacing.XXS),
            )
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(
                onClick = if (downloaded) onDelete else onDownload,
                enabled = enabled,
            ) {
                Text(
                    stringResource(
                        if (downloaded) R.string.models_delete else R.string.models_download,
                    ),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ModelsPreview() {
    LocalAiTheme {
        ModelsContent(
            state = ModelsUiState(
                languages = listOf(
                    TranslationLanguage("en", "English"),
                    TranslationLanguage("es", "Spanish"),
                    TranslationLanguage("fr", "French"),
                ),
                downloaded = setOf("en"),
                isLoading = false,
            ),
            onQueryChange = {},
            onDownload = {},
            onDelete = {},
            onNavigateUp = {},
        )
    }
}
