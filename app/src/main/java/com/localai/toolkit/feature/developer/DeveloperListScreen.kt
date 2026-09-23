package com.localai.toolkit.feature.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.component.StatusChip
import com.localai.toolkit.core.designsystem.component.StatusTone
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing

/**
 * The developer tool index.
 *
 * Split into "works everywhere" and "needs on-device AI" because that is the distinction
 * a developer actually cares about here: the deterministic utilities are guaranteed, the
 * explanation tools depend on the hardware.
 */
@Composable
fun DeveloperListScreen(
    onOpenTool: (DeveloperTool) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aiTools = DeveloperTool.entries.filter { it.needsAi }
    val localTools = DeveloperTool.entries.filterNot { it.needsAi }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.tool_developer_title),
                onNavigateUp = onNavigateUp,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.ScreenHorizontal,
                end = Spacing.ScreenHorizontal,
                bottom = Spacing.Huge,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            item {
                SectionHeader(
                    title = stringResource(R.string.dev_section_utilities),
                    subtitle = stringResource(R.string.dev_section_utilities_subtitle),
                )
            }

            items(localTools, key = { it.name }) { tool ->
                DeveloperToolRow(tool = tool, onClick = { onOpenTool(tool) })
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.dev_section_ai),
                    subtitle = stringResource(R.string.dev_section_ai_subtitle),
                )
            }

            items(aiTools, key = { it.name }) { tool ->
                DeveloperToolRow(tool = tool, onClick = { onOpenTool(tool) })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier.padding(top = Spacing.XXL, bottom = Spacing.S),
        verticalArrangement = Arrangement.spacedBy(Spacing.XXS),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeveloperToolRow(tool: DeveloperTool, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.L)) {
            Text(
                text = stringResource(tool.titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(tool.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.XXS),
            )
            if (!tool.needsAi) {
                StatusChip(
                    label = stringResource(R.string.dev_no_ai_needed),
                    tone = StatusTone.Ready,
                    modifier = Modifier.padding(top = Spacing.S),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeveloperListPreview() {
    LocalAiTheme { DeveloperListScreen(onOpenTool = {}, onNavigateUp = {}) }
}
