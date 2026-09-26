package com.localai.toolkit.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.StatusChip
import com.localai.toolkit.core.designsystem.component.StatusTone
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.theme.FieldNotesCanvas
import com.localai.toolkit.core.designsystem.theme.FieldNotesMustard
import com.localai.toolkit.core.designsystem.theme.FieldNotesMustardLight
import com.localai.toolkit.core.designsystem.theme.FieldNotesSage
import com.localai.toolkit.core.designsystem.theme.FieldNotesSageLight
import com.localai.toolkit.core.designsystem.theme.FieldNotesSky
import com.localai.toolkit.core.designsystem.theme.FieldNotesSkyLight
import com.localai.toolkit.core.designsystem.theme.FieldNotesTomato
import com.localai.toolkit.core.designsystem.theme.FieldNotesTomatoLight
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.navigation.ToolCatalog
import com.localai.toolkit.core.util.formatRelativeTime
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ToolId

@Composable
fun HomeScreen(
    onOpenTool: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHistoryItem: (Long) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onOpenTool = onOpenTool,
        onOpenHistoryItem = onOpenHistoryItem,
        modifier = modifier,
        onRetryHistory = viewModel::retryHistory,
        onRetryCapabilities = viewModel::refresh,
    )
}

@Composable
internal fun HomeContent(
    state: HomeUiState,
    onOpenTool: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHistoryItem: (Long) -> Unit = {},
    onRetryHistory: () -> Unit = {},
    onRetryCapabilities: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Image(
            painter = painterResource(R.drawable.field_notes_paper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.42f),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.Huge),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
        item { WorkspaceHeader(readiness = state.readiness) }

        if (state.capabilityCheckFailed) {
            item {
                ErrorCard(
                    message = stringResource(R.string.gate_check_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetryCapabilities,
                    modifier = Modifier.padding(horizontal = Spacing.ScreenHorizontal),
                )
            }
        }

        item {
            val targets = state.newTaskTargets()
            NewTaskCard(
                targets = targets,
                onOpenTool = { onOpenTool(ToolCatalog[it].route) },
                modifier = Modifier.padding(horizontal = Spacing.ScreenHorizontal),
            )
        }

        item {
            EditorialSectionLabel(
                text = stringResource(R.string.home_start_with),
                modifier = Modifier.padding(
                    start = Spacing.ScreenHorizontal,
                    end = Spacing.ScreenHorizontal,
                    top = Spacing.L,
                ),
            )
        }

        itemsIndexed(
            items = state.tools,
            key = { _, item -> item.toolId.name },
        ) { index, tool ->
            val entry = ToolCatalog[tool.toolId]
            ToolRow(
                title = stringResource(entry.titleRes),
                description = stringResource(entry.descriptionRes),
                icon = entry.icon,
                accent = toolAccent(index),
                accentContainer = toolAccentContainer(index),
                enabled = tool.enabled,
                statusLabel = tool.status.label(),
                statusTone = tool.status.tone(),
                onClick = { onOpenTool(entry.route) },
                modifier = Modifier.padding(horizontal = Spacing.ScreenHorizontal),
            )
        }

        item {
            EditorialSectionLabel(
                text = stringResource(R.string.home_recent),
                modifier = Modifier.padding(
                    start = Spacing.ScreenHorizontal,
                    end = Spacing.ScreenHorizontal,
                    top = Spacing.XL,
                ),
            )
        }

        item {
            if (state.historyLoading) {
                LoadingState(label = stringResource(R.string.history_loading))
            } else if (state.historyFailed) {
                ErrorCard(
                    message = stringResource(R.string.history_load_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetryHistory,
                    modifier = Modifier.padding(horizontal = Spacing.ScreenHorizontal),
                )
            } else RecentPanel(
                items = state.recentItems,
                onOpenItem = onOpenHistoryItem,
                modifier = Modifier.padding(horizontal = Spacing.ScreenHorizontal),
            )
        }

        item {
            DevicePanel(
                readiness = state.readiness,
                modifier = Modifier.padding(horizontal = Spacing.ScreenHorizontal),
            )
        }
        }
    }
}

@Composable
private fun WorkspaceHeader(readiness: DeviceReadiness) {
    Surface(color = FieldNotesCanvas) {
        if (LocalDensity.current.fontScale >= 1.6f) {
            // Let enlarged text determine the header height instead of overlapping the
            // decorative hero and the absolutely positioned title in the compact layout.
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = Spacing.ScreenHorizontal,
                    vertical = Spacing.XL,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.L),
            ) {
                WorkspaceBrand()
                WorkspaceSummary(readiness, Modifier.fillMaxWidth())
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(272.dp)
                    .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.XL),
            ) {
                WorkspaceBrand(modifier = Modifier.align(Alignment.TopStart))

                Image(
                    painter = painterResource(R.drawable.field_notes_hero),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 38.dp)
                        .width(188.dp)
                        .height(126.dp),
                )

                WorkspaceSummary(
                    readiness = readiness,
                    modifier = Modifier.align(Alignment.BottomStart).widthIn(max = 330.dp),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceBrand(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(7.dp).size(22.dp),
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Spacing.M),
            )
        }
        Text(
            text = stringResource(R.string.home_field_notes_tagline).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.XS),
        )
    }
}

@Composable
private fun WorkspaceSummary(readiness: DeviceReadiness, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(
            text = stringResource(R.string.home_workspace_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(FieldNotesSage, CircleShape))
            Text(
                text = stringResource(R.string.home_workspace_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.S),
            )
        }
        StatusChip(
            label = stringResource(readiness.labelRes()),
            tone = readiness.tone(),
            contentDescription = stringResource(readiness.descriptionRes()),
        )
    }
}

@Composable
private fun NewTaskCard(
    targets: NewTaskTargets,
    onOpenTool: (ToolId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shortcuts = buildList {
        targets.text?.let { target ->
            add(TaskShortcut(
                Icons.Outlined.Description,
                if (target == ToolId.ASK) R.string.home_add_text else R.string.home_translate_text,
                target,
            ))
        }
        targets.image?.let { target ->
            add(TaskShortcut(
                Icons.Outlined.AddPhotoAlternate,
                if (target == ToolId.IMAGE) R.string.home_add_image else R.string.home_extract_text,
                target,
            ))
        }
        targets.audio?.let { target ->
            add(TaskShortcut(Icons.Outlined.GraphicEq, R.string.home_add_audio, target))
        }
    }
    Surface(
        onClick = { targets.primary?.let(onOpenTool) },
        enabled = targets.primary != null,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(Spacing.XL)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_new_task),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = stringResource(
                            if (targets.audio == null) R.string.home_new_task_body_no_audio
                            else R.string.home_new_task_body,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = Spacing.XS),
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = Spacing.L)) {
                val stackActions = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.2f ||
                    targets.text != ToolId.ASK || targets.image != ToolId.IMAGE
                if (stackActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        shortcuts.forEach { shortcut ->
                            TaskAction(shortcut.icon, shortcut.labelRes,
                                { onOpenTool(shortcut.target) }, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        shortcuts.forEach { shortcut ->
                            TaskAction(shortcut.icon, shortcut.labelRes,
                                { onOpenTool(shortcut.target) })
                        }
                    }
                }
            }
        }
    }
}

private data class TaskShortcut(
    val icon: ImageVector,
    @StringRes val labelRes: Int,
    val target: ToolId,
)

@Composable
private fun TaskAction(
    icon: ImageVector,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.heightIn(min = Spacing.MinTouchTarget)
                .padding(horizontal = Spacing.M, vertical = Spacing.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = Spacing.S),
            )
        }
    }
}

@Composable
private fun EditorialSectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.width(Spacing.M))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun ToolRow(
    title: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    accentContainer: Color,
    enabled: Boolean,
    statusLabel: String?,
    statusTone: StatusTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.55f)
                .padding(Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = accentContainer, shape = MaterialTheme.shapes.small) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(Spacing.M).size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.M)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.XXS),
                )
                if (statusLabel != null && statusTone != StatusTone.Ready) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.XS),
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentPanel(
    items: List<HistoryItem>,
    onOpenItem: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(126.dp)) {
                Image(
                    painter = painterResource(R.drawable.field_notes_landscape),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.BottomEnd).width(210.dp).alpha(0.72f),
                )
                Text(
                    text = stringResource(R.string.home_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopStart).padding(Spacing.L),
                )
            }
        } else {
            Column(modifier = Modifier.padding(vertical = Spacing.XS)) {
                items.forEachIndexed { index, item ->
                    HistoryRow(item = item, onClick = { onOpenItem(item.id) })
                    if (index != items.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.L)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.L),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = FieldNotesSageLight, shape = MaterialTheme.shapes.small) {
                Icon(
                    imageVector = historyIcon(item.type),
                    contentDescription = null,
                    tint = FieldNotesSage,
                    modifier = Modifier.padding(Spacing.S).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.M)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatRelativeTime(item.createdAtEpochMillis).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DevicePanel(readiness: DeviceReadiness, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(modifier = Modifier.padding(Spacing.L), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.secondary, shape = MaterialTheme.shapes.small) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.padding(Spacing.S).size(22.dp),
                )
            }
            Column(modifier = Modifier.padding(start = Spacing.M)) {
                Text(
                    text = stringResource(
                        when (readiness) {
                            DeviceReadiness.READY -> R.string.home_models_ready
                            DeviceReadiness.CHECKING -> R.string.home_models_checking
                            DeviceReadiness.MODEL_REQUIRED -> R.string.home_status_model_required
                            DeviceReadiness.LIMITED -> R.string.home_status_limited
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(readiness.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

private fun toolAccent(index: Int): Color = when (index % 4) {
    0 -> FieldNotesTomato
    1 -> FieldNotesMustard
    2 -> FieldNotesSky
    else -> FieldNotesSage
}

private fun toolAccentContainer(index: Int): Color = when (index % 4) {
    0 -> FieldNotesTomatoLight
    1 -> FieldNotesMustardLight
    2 -> FieldNotesSkyLight
    else -> FieldNotesSageLight
}

private fun historyIcon(type: HistoryType): ImageVector = when (type) {
    HistoryType.ASK -> ToolCatalog[ToolId.ASK].icon
    HistoryType.SUMMARY -> ToolCatalog[ToolId.SUMMARIZE].icon
    HistoryType.REWRITE -> ToolCatalog[ToolId.REWRITE].icon
    HistoryType.PROOFREAD -> ToolCatalog[ToolId.PROOFREAD].icon
    HistoryType.OCR -> ToolCatalog[ToolId.OCR].icon
    HistoryType.TRANSLATION -> ToolCatalog[ToolId.TRANSLATE].icon
    HistoryType.IMAGE_DESCRIPTION -> ToolCatalog[ToolId.IMAGE].icon
    HistoryType.TRANSCRIPTION -> ToolCatalog[ToolId.TRANSCRIBE].icon
    HistoryType.DEVELOPER -> ToolCatalog[ToolId.DEVELOPER].icon
}

private fun DeviceReadiness.labelRes(): Int = when (this) {
    DeviceReadiness.CHECKING -> R.string.home_status_checking
    DeviceReadiness.READY -> R.string.home_status_ready
    DeviceReadiness.MODEL_REQUIRED -> R.string.home_status_model_required
    DeviceReadiness.LIMITED -> R.string.home_status_limited
}

private fun DeviceReadiness.descriptionRes(): Int = when (this) {
    DeviceReadiness.CHECKING -> R.string.loading_checking_availability
    DeviceReadiness.READY -> R.string.home_status_ready_description
    DeviceReadiness.MODEL_REQUIRED -> R.string.home_status_model_required_description
    DeviceReadiness.LIMITED -> R.string.home_status_limited_description
}

private fun DeviceReadiness.tone(): StatusTone = when (this) {
    DeviceReadiness.READY -> StatusTone.Ready
    DeviceReadiness.MODEL_REQUIRED -> StatusTone.Pending
    DeviceReadiness.LIMITED -> StatusTone.Unsupported
    DeviceReadiness.CHECKING -> StatusTone.Neutral
}

@Composable
private fun AiCapabilityStatus.label(): String? = when (this) {
    AiCapabilityStatus.AVAILABLE -> stringResource(R.string.capability_on_device)
    AiCapabilityStatus.DOWNLOADABLE -> stringResource(R.string.capability_download_required)
    AiCapabilityStatus.DOWNLOADING -> stringResource(R.string.capability_downloading)
    AiCapabilityStatus.UNSUPPORTED -> stringResource(R.string.capability_unsupported)
    AiCapabilityStatus.TEMPORARILY_UNAVAILABLE -> stringResource(R.string.capability_temporarily_unavailable)
    AiCapabilityStatus.ERROR -> stringResource(R.string.capability_error)
    AiCapabilityStatus.UNKNOWN -> stringResource(R.string.capability_checking)
}

private fun AiCapabilityStatus.tone(): StatusTone = when (this) {
    AiCapabilityStatus.AVAILABLE -> StatusTone.Ready
    AiCapabilityStatus.DOWNLOADABLE, AiCapabilityStatus.DOWNLOADING -> StatusTone.Pending
    AiCapabilityStatus.UNSUPPORTED -> StatusTone.Unsupported
    AiCapabilityStatus.TEMPORARILY_UNAVAILABLE -> StatusTone.Pending
    AiCapabilityStatus.ERROR -> StatusTone.Unsupported
    AiCapabilityStatus.UNKNOWN -> StatusTone.Neutral
}

@Preview(name = "Field Notes home", showBackground = true)
@Composable
private fun HomeSupportedPreview() {
    LocalAiTheme {
        HomeContent(state = HomePreviewStates.supported, onOpenTool = {})
    }
}
