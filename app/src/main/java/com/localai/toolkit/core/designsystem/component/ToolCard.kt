package com.localai.toolkit.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.designsystem.theme.motionTween

/**
 * A tool tile on Home.
 *
 * Unsupported tools are shown dimmed with an explanation rather than hidden, so the user
 * learns what their device can and cannot do. A dimmed card is still focusable and still
 * announces its reason to TalkBack; only the tap action is disabled.
 *
 * @param statusLabel short availability line, or null when the tool is always available.
 * @param enabled false dims the card and blocks the tap.
 */
@Composable
fun ToolCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
    statusTone: StatusTone = StatusTone.Ready,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // A small press-scale reads as physical feedback without being noisy. It collapses
    // to an instant change when the user has reduced motion enabled.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = motionTween(),
        label = "toolCardPressScale",
    )

    Card(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .semantics {
                if (statusLabel != null) stateDescription = statusLabel
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // Keep the disabled card readable rather than washed out; the dimming is
            // applied to content below so the border still defines the shape.
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .padding(Spacing.L)
                .heightIn(min = 148.dp)
                .alpha(if (enabled) 1f else 0.55f),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = icon,
                    // The title already names the tool; a second announcement would be
                    // redundant for screen reader users.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (statusLabel != null) {
                Box(modifier = Modifier.padding(top = Spacing.XXS)) {
                    StatusChip(label = statusLabel, tone = statusTone)
                }
            }
        }
    }
}
