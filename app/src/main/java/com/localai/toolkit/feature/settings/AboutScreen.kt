package com.localai.toolkit.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.localai.toolkit.BuildConfig
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.navigation.Destination

@Composable
fun AboutScreen(
    onNavigateUp: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val openLicenses = licenseLauncher(snackbarHostState)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.settings_section_about),
                onNavigateUp = onNavigateUp,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(
                title = stringResource(R.string.app_name),
                summary = stringResource(R.string.app_tagline),
            )
            SettingsRow(
                title = stringResource(R.string.settings_version),
                summary = BuildConfig.VERSION_NAME,
            )
            SettingsRow(
                title = stringResource(R.string.settings_licenses),
                summary = stringResource(R.string.about_licenses_summary),
                onClick = openLicenses,
            )
            SettingsRow(
                title = stringResource(R.string.settings_device_info),
                summary = stringResource(R.string.settings_device_ai_summary),
                onClick = { onNavigate(Destination.DEVICE_AI) },
            )
            Text(
                text = stringResource(R.string.about_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = Spacing.ScreenHorizontal,
                    vertical = Spacing.XXL,
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutPreview() {
    LocalAiTheme { AboutScreen(onNavigateUp = {}, onNavigate = {}) }
}
