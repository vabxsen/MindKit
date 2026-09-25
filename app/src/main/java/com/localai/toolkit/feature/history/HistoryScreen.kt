package com.localai.toolkit.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.EmptyState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.designsystem.theme.FieldNotesTomato
import com.localai.toolkit.core.util.formatRelativeTime
import com.localai.toolkit.core.util.labelRes
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType

@Composable
fun HistoryScreen(
    onOpenItem: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val queryText by viewModel.queryText.collectAsStateWithLifecycle()
    val deletionState by viewModel.deletionState.collectAsStateWithLifecycle()

    HistoryContent(
        state = state,
        deletionState = deletionState,
        onRetryLoad = viewModel::refresh,
        onRetryDelete = viewModel::retryDeletion,
        queryText = queryText,
        onQueryChange = viewModel::onQueryChange,
        onToggleType = viewModel::onToggleType,
        onClearFilters = viewModel::onClearFilters,
        onDeleteAll = viewModel::onDeleteAll,
        onDelete = viewModel::onDelete,
        onOpenItem = onOpenItem,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    queryText: String,
    onQueryChange: (String) -> Unit,
    onToggleType: (HistoryType) -> Unit,
    onClearFilters: () -> Unit,
    onDeleteAll: () -> Unit,
    onDelete: (Long) -> Unit,
    onOpenItem: (Long) -> Unit,
    modifier: Modifier = Modifier,
    deletionState: HistoryDeletionState = HistoryDeletionState.IDLE,
    onRetryLoad: () -> Unit = {},
    onRetryDelete: () -> Unit = {},
) {
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Spacing.ScreenHorizontal,
                        end = Spacing.S,
                        top = Spacing.L,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                ) {
                    Text(
                        text = stringResource(R.string.history_title),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = stringResource(R.string.history_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.XS),
                    )
                }
                if (state.items.isNotEmpty()) {
                    IconButton(onClick = { showDeleteAllDialog = true }, enabled = deletionState != HistoryDeletionState.RUNNING) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete_all),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = queryText,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text(stringResource(R.string.history_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.M),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.ScreenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                items(HistoryType.entries.toList(), key = { it.name }) { type ->
                    val selected = type in state.selectedTypes
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleType(type) },
                        label = { Text(stringResource(type.labelRes())) },
                    )
                }
            }

            if (deletionState == HistoryDeletionState.FAILED) {
                ErrorCard(
                    message = stringResource(R.string.history_delete_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetryDelete,
                    modifier = Modifier.padding(Spacing.M),
                )
            }

            when {
                state.isLoading -> LoadingState(label = stringResource(R.string.history_loading))
                state.loadFailed -> ErrorCard(
                    message = stringResource(R.string.history_load_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetryLoad,
                    modifier = Modifier.padding(Spacing.M),
                )
                state.isEmptyStore -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.history_empty_title),
                    description = stringResource(R.string.history_empty_body),
                )

                state.isFilteredEmpty -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.history_no_matches_title),
                    description = stringResource(R.string.history_no_matches_body),
                    actionLabel = stringResource(R.string.action_clear),
                    onAction = onClearFilters,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.ScreenHorizontal,
                        end = Spacing.ScreenHorizontal,
                        top = Spacing.M,
                        bottom = Spacing.XXXL,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        HistoryRow(
                            item = item,
                            onClick = { onOpenItem(item.id) },
                            onDelete = { onDelete(item.id) },
                            deleteEnabled = deletionState != HistoryDeletionState.RUNNING,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_delete_all_title)) },
            text = { Text(stringResource(R.string.history_delete_all_body)) },
            confirmButton = {
                TextButton(
                    enabled = deletionState != HistoryDeletionState.RUNNING,
                    onClick = {
                        onDeleteAll()
                        showDeleteAllDialog = false
                    },
                ) { Text(stringResource(R.string.action_delete_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.L, top = Spacing.M, bottom = Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(item.type.labelRes()),
                        style = MaterialTheme.typography.labelMedium,
                        color = FieldNotesTomato,
                    )
                    Text(
                        text = formatRelativeTime(item.createdAtEpochMillis).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.XXS),
                )
                Text(
                    text = item.output,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.XXS),
                )
            }
            IconButton(onClick = onDelete, enabled = deleteEnabled) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "History - empty", showBackground = true)
@Composable
private fun HistoryEmptyPreview() {
    LocalAiTheme {
        HistoryContent(
            state = HistoryUiState(isLoading = false),
            queryText = "",
            onQueryChange = {},
            onToggleType = {},
            onClearFilters = {},
            onDeleteAll = {},
            onDelete = {},
            onOpenItem = {},
        )
    }
}

@Preview(name = "History - populated", showBackground = true)
@Composable
private fun HistoryPopulatedPreview() {
    LocalAiTheme {
        HistoryContent(
            state = HistoryUiState(
                isLoading = false,
                items = listOf(
                    HistoryItem(
                        id = 1,
                        type = HistoryType.SUMMARY,
                        title = "Quarterly planning notes",
                        inputPreview = "The team agreed to…",
                        output = "- Ship the beta in March\n- Hire one designer",
                        createdAtEpochMillis = System.currentTimeMillis() - 600_000,
                    ),
                    HistoryItem(
                        id = 2,
                        type = HistoryType.OCR,
                        title = "Receipt",
                        inputPreview = "image",
                        output = "TOTAL 18.40",
                        createdAtEpochMillis = System.currentTimeMillis() - 7_200_000,
                    ),
                ),
            ),
            queryText = "",
            onQueryChange = {},
            onToggleType = {},
            onClearFilters = {},
            onDeleteAll = {},
            onDelete = {},
            onOpenItem = {},
        )
    }
}
