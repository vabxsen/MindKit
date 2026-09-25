package com.localai.toolkit.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.localai.toolkit.AppUiState
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode

/** Retain the navigation graph and drafts under a recoverable preferences error. */
@Composable
internal fun AppRoot(state: AppUiState, onRetry: () -> Unit, content: @Composable (AppSettings) -> Unit) {
    val settings = when (state) {
        is AppUiState.Ready -> state.settings
        is AppUiState.Failed -> state.previousSettings
        AppUiState.Loading -> null
    }
    LocalAiTheme(
        themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
        dynamicColor = settings?.dynamicColor ?: false,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) {
                if (settings != null) {
                    content(settings)
                } else if (state is AppUiState.Failed) {
                    ErrorCard(
                        message = stringResource(R.string.settings_load_failed),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRetry,
                        modifier = Modifier.safeDrawingPadding().padding(Spacing.M),
                    )
                } else {
                    LoadingState(label = stringResource(R.string.settings_loading), modifier = Modifier.safeDrawingPadding())
                }
                if (state is AppUiState.Failed && settings != null) {
                    AlertDialog(
                        onDismissRequest = {},
                        text = { Text(stringResource(R.string.settings_load_failed)) },
                        confirmButton = {
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        },
                    )
                }
            }
        }
    }
}
