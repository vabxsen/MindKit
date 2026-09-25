package com.localai.toolkit.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.EmptyState
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.ui.rememberTextActionHandler
import com.localai.toolkit.core.util.labelRes
import com.localai.toolkit.domain.model.HistoryItem

@Composable
fun HistoryDetailScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryDetailPage(state, onNavigateUp, viewModel::refresh, modifier)
}

@Composable
internal fun HistoryDetailPage(
    state: HistoryDetailUiState,
    onNavigateUp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item
    val snackbarHostState = remember { SnackbarHostState() }
    val textActions = rememberTextActionHandler(snackbarHostState)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = item?.let { stringResource(it.type.labelRes()) }
                    ?: stringResource(R.string.history_detail_title),
                onNavigateUp = onNavigateUp,
                actions = {
                    val current = item
                    if (current != null) {
                        IconButton(
                            onClick = { textActions.copy(current.title, current.output) },
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.action_copy),
                            )
                        }
                        IconButton(onClick = { textActions.share(current.output) }) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.action_share),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = item
        if (state.isLoading) {
            LoadingState(label = stringResource(R.string.history_loading), modifier = Modifier.padding(padding))
        } else if (state.loadFailed) {
            ErrorCard(
                message = stringResource(R.string.history_load_failed),
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
                modifier = Modifier.padding(padding).padding(Spacing.M),
            )
        } else if (current == null) {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.history_detail_missing),
                description = stringResource(R.string.history_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            HistoryDetailContent(
                item = current,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun HistoryDetailContent(item: HistoryItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.ScreenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Section(
            label = stringResource(R.string.history_detail_input),
            body = item.inputPreview,
        )
        Section(
            label = stringResource(R.string.history_detail_output),
            body = item.output,
            emphasised = true,
        )
        item.metadata?.let { metadata ->
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.XXL),
            )
        }
    }
}

@Composable
private fun Section(label: String, body: String, emphasised: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = if (emphasised) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(Spacing.L),
            )
        }
    }
}
