package com.localai.toolkit.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.component.EmptyState
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.ui.messageRes

/**
 * Renders the shared availability states, or [content] once the feature is usable.
 *
 * Every Gemini Nano tool wraps its body in this, which is what guarantees that an
 * unsupported device gets a clear explanation rather than a broken screen, and that no
 * tool ever fakes a result it could not produce.
 */
@Composable
fun GenAiGate(
    gateState: GateState,
    downloadState: ModelDownloadState,
    onDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (gateState) {
        GateState.READY -> content()

        GateState.CHECKING -> LoadingState(
            label = stringResource(R.string.loading_checking_availability),
            modifier = modifier,
        )

        GateState.DOWNLOADING, GateState.DOWNLOADING_CHECK_FAILED -> {
            val progress = (downloadState as? ModelDownloadState.InProgress)?.fraction
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                LoadingState(
                    label = if (progress != null) {
                        stringResource(
                            R.string.loading_downloading_model_percent,
                            (progress * 100).toInt(),
                        )
                    } else {
                        stringResource(R.string.loading_downloading_model)
                    },
                    progress = progress,
                )
                if (gateState == GateState.DOWNLOADING_CHECK_FAILED) {
                    ErrorCard(message = stringResource(R.string.gate_check_failed))
                }
                Button(onClick = onRetryCheck, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_check_status))
                }
            }
        }

        GateState.NEEDS_DOWNLOAD -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            EmptyState(
                icon = Icons.Outlined.DownloadForOffline,
                title = stringResource(R.string.gate_download_title),
                description = stringResource(R.string.gate_download_body),
                fillAvailableSpace = false,
            )
            // A failed attempt is reported above the button so the user can see why
            // retrying might be worth it.
            (downloadState as? ModelDownloadState.Failed)?.let { failed ->
                ErrorCard(message = stringResource(failed.reason.messageRes()))
            }
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_download_model))
            }
        }

        GateState.UNSUPPORTED -> EmptyState(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.gate_unsupported_title),
            description = stringResource(R.string.gate_unsupported_body),
            modifier = modifier,
        )

        GateState.ERROR -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Text(
                text = stringResource(R.string.gate_check_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetryCheck, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
