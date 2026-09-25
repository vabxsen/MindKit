package com.localai.toolkit.core.designsystem.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.designsystem.theme.FieldNotesTomato

/**
 * The standard "here is your result" block.
 *
 * Every tool presents its output through this, so copy, share and save behave and look
 * the same everywhere, and the result text is always selectable.
 *
 * @param saved when true the save action shows as done rather than offering again.
 * @param secondaryActions cross-tool follow-ups, rendered as chips beneath the actions.
 */
@Composable
fun ResultCard(
    text: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
    saved: Boolean = false,
    saveEnabled: Boolean = true,
    label: String? = null,
    textStyle: TextStyle? = null,
    secondaryActions: List<ResultAction> = emptyList(),
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(Spacing.L)) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = FieldNotesTomato,
                    modifier = Modifier.padding(bottom = Spacing.S),
                )
            }

            SelectionContainer {
                Text(
                    text = text,
                    style = textStyle ?: MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(
                modifier = Modifier.padding(top = Spacing.S),
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCopy) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_copy),
                        modifier = Modifier.padding(start = Spacing.S),
                    )
                }
                TextButton(onClick = onShare) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_share),
                        modifier = Modifier.padding(start = Spacing.S),
                    )
                }
                if (onSave != null) {
                    TextButton(onClick = onSave, enabled = !saved && saveEnabled) {
                        Icon(
                            imageVector = if (saved) Icons.Outlined.Check else Icons.Outlined.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                if (saved) R.string.action_saved else R.string.action_save,
                            ),
                            modifier = Modifier.padding(start = Spacing.S),
                        )
                    }
                }
            }

            if (secondaryActions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = Spacing.XS),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    secondaryActions.forEach { action ->
                        AssistChip(
                            onClick = action.onClick,
                            label = { Text(action.label) },
                        )
                    }
                }
            }
        }
    }
}

/** A follow-up the result can be sent to, such as "Summarize" after extracting text. */
data class ResultAction(val label: String, val onClick: () -> Unit)
