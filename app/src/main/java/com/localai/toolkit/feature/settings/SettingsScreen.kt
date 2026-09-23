package com.localai.toolkit.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.BuildConfig
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode

@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val historyClearedMessage = stringResource(R.string.settings_clear_history)
    val dataClearedMessage = stringResource(R.string.settings_clear_data_done)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                SettingsEvent.HISTORY_CLEARED -> historyClearedMessage
                SettingsEvent.DATA_CLEARED -> dataClearedMessage
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    SettingsContent(
        settings = settings,
        dynamicColorSupported = viewModel.dynamicColorSupported,
        snackbarHostState = snackbarHostState,
        onThemeModeChange = viewModel::setThemeMode,
        onDynamicColorChange = viewModel::setDynamicColor,
        onSaveHistoryChange = viewModel::setSaveHistory,
        onVerboseErrorsChange = viewModel::setVerboseErrors,
        onClearHistory = viewModel::clearHistory,
        onClearAllData = viewModel::clearAllLocalData,
        onNavigate = onNavigate,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsContent(
    settings: AppSettings,
    dynamicColorSupported: Boolean,
    snackbarHostState: SnackbarHostState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSaveHistoryChange: (Boolean) -> Unit,
    onVerboseErrorsChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClearAllData: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearDataDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.ScreenHorizontal)
                    .padding(top = Spacing.L)
                    .semantics { heading() },
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.XS),
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(
                            horizontal = Spacing.ScreenHorizontal,
                            vertical = Spacing.S,
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = Spacing.ScreenHorizontal,
                                vertical = Spacing.XS,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                label = { Text(stringResource(mode.labelRes())) },
                            )
                        }
                    }
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        summary = if (dynamicColorSupported) {
                            stringResource(R.string.settings_dynamic_color_summary)
                        } else {
                            stringResource(R.string.settings_dynamic_color_unsupported)
                        },
                        checked = settings.dynamicColor && dynamicColorSupported,
                        enabled = dynamicColorSupported,
                        onCheckedChange = onDynamicColorChange,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_ai)) {
                Column {
                    SettingsRow(
                        title = stringResource(R.string.settings_device_ai),
                        summary = stringResource(R.string.settings_device_ai_summary),
                        onClick = { onNavigate(Destination.DEVICE_AI) },
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_models),
                        summary = stringResource(R.string.settings_models_summary),
                        onClick = { onNavigate(Destination.MODELS) },
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_privacy)) {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_save_history),
                        summary = stringResource(R.string.settings_save_history_summary),
                        checked = settings.saveHistory,
                        onCheckedChange = onSaveHistoryChange,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_clear_history),
                        summary = stringResource(R.string.settings_clear_history_summary),
                        onClick = onClearHistory,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_clear_data),
                        summary = stringResource(R.string.settings_clear_data_summary),
                        onClick = { showClearDataDialog = true },
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_privacy_info),
                        onClick = { onNavigate(Destination.PRIVACY) },
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                Column {
                    SettingsRow(
                        title = stringResource(R.string.settings_version),
                        summary = BuildConfig.VERSION_NAME,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_licenses),
                        onClick = { onNavigate(Destination.ABOUT) },
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_developer)) {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_verbose_errors),
                        summary = stringResource(R.string.settings_verbose_errors_summary),
                        checked = settings.verboseErrors,
                        onCheckedChange = onVerboseErrorsChange,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_debug_capability),
                        onClick = { onNavigate(Destination.DEVICE_AI) },
                    )
                }
            }

            Column(modifier = Modifier.padding(bottom = Spacing.XXXL)) {}
        }
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text(stringResource(R.string.settings_clear_data_title)) },
            text = { Text(stringResource(R.string.settings_clear_data_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAllData()
                        showClearDataDialog = false
                    },
                ) { Text(stringResource(R.string.settings_clear_data)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    LocalAiTheme {
        SettingsContent(
            settings = AppSettings(),
            dynamicColorSupported = true,
            snackbarHostState = remember { SnackbarHostState() },
            onThemeModeChange = {},
            onDynamicColorChange = {},
            onSaveHistoryChange = {},
            onVerboseErrorsChange = {},
            onClearHistory = {},
            onClearAllData = {},
            onNavigate = {},
        )
    }
}
