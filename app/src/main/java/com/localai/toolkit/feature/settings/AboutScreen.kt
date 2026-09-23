package com.localai.toolkit.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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
                onClick = {
                    // The platform licence viewer is the honest place for this: the app
                    // does not bundle its own copy of every dependency notice.
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).setData(LICENSES_URI))
                    }
                },
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

private val LICENSES_URI = android.net.Uri.parse("https://developers.google.com/ml-kit/terms")

@Preview(showBackground = true)
@Composable
private fun AboutPreview() {
    LocalAiTheme { AboutScreen(onNavigateUp = {}, onNavigate = {}) }
}
