package com.localai.toolkit.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing

/**
 * The privacy statement.
 *
 * Every claim here is one the implementation actually backs, and the caveat section
 * states plainly what the app cannot promise, because the on-device models belong to the
 * platform rather than to this app.
 */
@Composable
fun PrivacyScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.privacy_title),
                onNavigateUp = onNavigateUp,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.ScreenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Text(
                text = stringResource(R.string.privacy_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = Spacing.S)
                    .semantics { heading() },
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                PrivacyPoint(stringResource(R.string.privacy_point_local))
                PrivacyPoint(stringResource(R.string.privacy_point_no_account))
                PrivacyPoint(stringResource(R.string.privacy_point_no_analytics))
                PrivacyPoint(stringResource(R.string.privacy_point_no_ads))
                PrivacyPoint(stringResource(R.string.privacy_point_no_tracking))
                PrivacyPoint(stringResource(R.string.privacy_point_no_upload))
            }

            PrivacyBlock(
                title = stringResource(R.string.privacy_permissions_title),
                lines = listOf(
                    stringResource(R.string.privacy_permission_internet),
                    stringResource(R.string.privacy_permission_microphone),
                    stringResource(R.string.privacy_permission_camera),
                ),
            )

            PrivacyBlock(
                title = stringResource(R.string.privacy_caveat_title),
                lines = listOf(stringResource(R.string.privacy_caveat_body)),
            )

            Column(modifier = Modifier.padding(bottom = Spacing.XXXL)) {}
        }
    }
}

@Composable
private fun PrivacyPoint(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PrivacyBlock(title: String, lines: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PrivacyPreview() {
    LocalAiTheme { PrivacyScreen(onNavigateUp = {}) }
}
