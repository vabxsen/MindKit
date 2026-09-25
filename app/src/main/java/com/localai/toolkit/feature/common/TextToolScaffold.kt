package com.localai.toolkit.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.localai.toolkit.R
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.theme.Spacing

/**
 * The shared layout for the text-in, text-out tools.
 *
 * Summarize, Rewrite and Proofread differ only in their options and how they present a
 * result, so everything else - app bar, availability gate, input field with a character
 * count, action button, clear button - is defined once here. That is what keeps the three
 * screens consistent and each one small enough to read at a glance.
 */
@Composable
fun TextToolScaffold(
    title: String,
    inputHint: String,
    actionLabel: String,
    input: String,
    onInputChange: (String) -> Unit,
    onAction: () -> Unit,
    actionEnabled: Boolean,
    gateState: GateState,
    downloadState: ModelDownloadState,
    onDownload: () -> Unit,
    onRetryCheck: () -> Unit,
    onClear: () -> Unit,
    onNavigateUp: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    options: @Composable () -> Unit = {},
    result: @Composable () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { LocalAiTopBar(title = title, onNavigateUp = onNavigateUp) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.ScreenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            GenAiGate(
                gateState = gateState,
                downloadState = downloadState,
                onDownload = onDownload,
                onRetryCheck = onRetryCheck,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        label = { Text(inputHint) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .padding(top = Spacing.M),
                        shape = MaterialTheme.shapes.large,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )

                    // A visible length is genuinely useful here: the on-device models
                    // have input limits, and a long paste is the usual reason a request
                    // is rejected.
                    Text(
                        text = pluralStringResource(R.plurals.summarize_char_count, input.length, input.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End),
                    )

                    options()

                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(actionLabel) }

                    result()

                    if (input.isNotBlank()) {
                        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.XXXL))
        }
    }
}

/**
 * A labelled row of single-choice chips.
 *
 * Used for the option rows in the text tools; kept here so they all space and align the
 * same way.
 */
@Composable
fun OptionGroup(
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
