package com.localai.toolkit.feature.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.navigation.ToolCatalog
import com.localai.toolkit.domain.model.ToolId

/**
 * Where shared content lands.
 *
 * Deliberately not Home. Someone who shares a paragraph into the app has already decided
 * what they want to work on; dropping them on a dashboard would make them find the tool,
 * open it and paste again. This screen shows the content and the handful of things that
 * can actually be done with it.
 */
@Composable
fun ShareRouterScreen(
    onOpenTool: (ToolId) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShareRouterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ShareRouterContent(
        state = state,
        onSelect = { tool ->
            viewModel.route(tool)
            onOpenTool(tool)
        },
        onCancel = {
            viewModel.cancel()
            onCancel()
        },
        modifier = modifier,
    )
}

@Composable
internal fun ShareRouterContent(
    state: ShareRouterUiState,
    onSelect: (ToolId) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.share_title),
                onNavigateUp = onCancel,
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
            Text(
                text = stringResource(state.kindLabelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.M),
            )

            if (state.previewText.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = state.previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(Spacing.L),
                    )
                }
            }

            Text(
                text = stringResource(R.string.share_what_would_you_like),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = Spacing.S)
                    .semantics { heading() },
            )

            state.actions.forEach { tool ->
                val entry = ToolCatalog[tool]
                Card(
                    onClick = { onSelect(tool) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(Spacing.L)) {
                        Text(
                            text = stringResource(entry.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(entry.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.XXS),
                        )
                    }
                }
            }

            if (state.actions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.XXL),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.share_nothing_to_do),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.M),
                    )
                }
            }

            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.XXL),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Preview(name = "Share - text", showBackground = true)
@Composable
private fun ShareTextPreview() {
    LocalAiTheme {
        ShareRouterContent(
            state = ShareRouterUiState(
                kindLabelRes = R.string.share_kind_text,
                previewText = "The quarterly review covered three themes...",
                actions = listOf(
                    ToolId.SUMMARIZE,
                    ToolId.REWRITE,
                    ToolId.PROOFREAD,
                    ToolId.TRANSLATE,
                    ToolId.ASK,
                ),
            ),
            onSelect = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Share - image", showBackground = true)
@Composable
private fun ShareImagePreview() {
    LocalAiTheme {
        ShareRouterContent(
            state = ShareRouterUiState(
                kindLabelRes = R.string.share_kind_image,
                previewText = "",
                actions = listOf(ToolId.OCR, ToolId.IMAGE),
            ),
            onSelect = {},
            onCancel = {},
        )
    }
}
