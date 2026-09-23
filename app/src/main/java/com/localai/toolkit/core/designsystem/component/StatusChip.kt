package com.localai.toolkit.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing

/** Visual weight of a [StatusChip]. Each tone always ships with its own text label. */
enum class StatusTone { Ready, Pending, Unsupported, Neutral }

/**
 * A small pill stating an availability fact.
 *
 * Colour is never the only signal: the label always carries the same information, and the
 * whole chip is exposed to TalkBack as one string so the dot is not announced separately.
 *
 * @param contentDescription overrides the announced text when [label] alone is ambiguous.
 */
@Composable
fun StatusChip(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
) {
    val status = LocalAiTheme.statusColors
    val container: Color
    val content: Color
    val dot: Color
    when (tone) {
        StatusTone.Ready -> {
            container = status.readyContainer
            content = status.onReadyContainer
            dot = status.ready
        }
        StatusTone.Pending -> {
            container = status.needsDownloadContainer
            content = status.onNeedsDownloadContainer
            dot = status.needsDownload
        }
        StatusTone.Unsupported -> {
            container = status.unsupportedContainer
            content = status.onUnsupportedContainer
            dot = status.unsupported
        }
        StatusTone.Neutral -> {
            container = MaterialTheme.colorScheme.surfaceContainerHigh
            content = MaterialTheme.colorScheme.onSurfaceVariant
            dot = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = Spacing.M, vertical = Spacing.XS + Spacing.XXS)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        StatusDot(color = dot, filled = tone != StatusTone.Unsupported)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

/**
 * The 8dp indicator.
 *
 * Unsupported uses a hollow ring rather than a different hue alone, so the distinction
 * survives greyscale and colour-blind viewing.
 */
@Composable
fun StatusDot(color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(8.dp)) {
        if (filled) {
            drawCircle(color = color)
        } else {
            drawCircle(color = color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
