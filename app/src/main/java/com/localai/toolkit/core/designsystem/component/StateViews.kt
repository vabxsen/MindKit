package com.localai.toolkit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localai.toolkit.core.designsystem.theme.Spacing

/**
 * A labelled busy state.
 *
 * Every loading state in the app says what it is waiting for - "Analysing locally",
 * "Preparing model" - because an unlabelled spinner tells the user nothing about whether
 * their data is leaving the device. The label is a live region so TalkBack announces
 * phase changes.
 */
@Composable
fun LoadingState(
    label: String,
    modifier: Modifier = Modifier,
    /** 0f..1f when the underlying API reports real progress, null for indeterminate. */
    progress: Float? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.XL)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        if (progress == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * An empty state with an icon, a headline and a supporting line.
 *
 * @param action optional call to action, e.g. "Choose a photo".
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    fillAvailableSpace: Boolean = true,
) {
    Column(
        modifier = modifier
            .then(if (fillAvailableSpace) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = Spacing.XXXL, vertical = Spacing.XXL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.L),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.S),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = Spacing.M),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * A recoverable error.
 *
 * [message] is always the friendly, mapped text. [technicalDetail] is only passed in when
 * the user has enabled verbose errors in Settings, so a normal user never sees a stack
 * trace or a vendor error code.
 */
@Composable
fun ErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    technicalDetail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.L)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                verticalAlignment = Alignment.Top,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (technicalDetail != null) {
                Text(
                    text = technicalDetail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Spacing.S),
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = Spacing.XS),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
