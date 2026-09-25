package com.localai.toolkit.feature.capability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.core.designsystem.component.LocalAiTopBar
import com.localai.toolkit.core.designsystem.component.StatusChip
import com.localai.toolkit.core.designsystem.component.StatusTone
import com.localai.toolkit.core.designsystem.component.ErrorCard
import com.localai.toolkit.core.designsystem.component.LoadingState
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.theme.Spacing
import com.localai.toolkit.ai.fake.FakeCapabilityManager
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.DeviceAiSnapshot

/**
 * The detailed capability report.
 *
 * Useful for the user ("why is Image AI greyed out?") and for debugging across phones.
 * Everything shown is a runtime result, never a guess from the device model name.
 */
@Composable
fun DeviceAiScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeviceAiViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshFailed by viewModel.refreshFailed.collectAsStateWithLifecycle()
    DeviceAiContent(
        snapshot = snapshot,
        onNavigateUp = onNavigateUp,
        onRefresh = viewModel::refresh,
        modifier = modifier,
        isRefreshing = isRefreshing,
        refreshFailed = refreshFailed,
    )
}

@Composable
internal fun DeviceAiContent(
    snapshot: DeviceAiSnapshot,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    isRefreshing: Boolean = false,
    refreshFailed: Boolean = false,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocalAiTopBar(
                title = stringResource(R.string.device_ai_title),
                onNavigateUp = onNavigateUp,
                actions = {
                    IconButton(onClick = onRefresh, enabled = !isRefreshing && !snapshot.isRefreshing) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.device_ai_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (isRefreshing || snapshot.isRefreshing) {
                LoadingState(label = stringResource(R.string.loading_checking_availability))
            }
            if (refreshFailed) {
                ErrorCard(
                    message = stringResource(R.string.gate_check_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRefresh,
                    modifier = Modifier.padding(Spacing.M),
                )
            }
            GeminiNanoSummary(snapshot)

            Text(
                text = stringResource(R.string.device_ai_capabilities),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = Spacing.ScreenHorizontal)
                    .padding(top = Spacing.XXL, bottom = Spacing.S)
                    .semantics { heading() },
            )

            AiTask.entries.forEach { task ->
                CapabilityRow(capability = snapshot[task])
            }

            Text(
                text = stringResource(R.string.device_ai_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.ScreenHorizontal)
                    .padding(top = Spacing.XXL, bottom = Spacing.Huge),
            )
        }
    }
}

@Composable
private fun GeminiNanoSummary(snapshot: DeviceAiSnapshot) {
    // The Prompt capability is the best single proxy for "is Gemini Nano usable here",
    // and it is also where a base model name is most likely to be reported.
    val prompt = snapshot[AiTask.ASK]
    val status = snapshot.nanoSummaryStatus()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.M),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.device_ai_gemini_nano),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                StatusChip(
                    label = stringResource(status.labelRes()),
                    tone = status.tone(),
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.device_ai_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = prompt.baseModelName
                        ?: stringResource(R.string.device_ai_unknown_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Readiness, not mere support: a missing/downloading model must not look ready. */
internal fun DeviceAiSnapshot.nanoSummaryStatus(): AiCapabilityStatus {
    val statuses = AiTask.entries.filter { it != AiTask.BASIC_TRANSCRIPTION &&
        it != AiTask.TEXT_RECOGNITION && it != AiTask.TRANSLATION }.map { this[it].status }
    return listOf(
        AiCapabilityStatus.AVAILABLE,
        AiCapabilityStatus.DOWNLOADING,
        AiCapabilityStatus.DOWNLOADABLE,
        AiCapabilityStatus.ERROR,
        AiCapabilityStatus.TEMPORARILY_UNAVAILABLE,
        AiCapabilityStatus.UNKNOWN,
    ).firstOrNull { it in statuses } ?: AiCapabilityStatus.UNSUPPORTED
}

/**
 * One capability line.
 *
 * Shared with onboarding so the two screens cannot drift apart.
 */
@Composable
fun CapabilityRow(capability: AiCapability, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ScreenHorizontal, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(capability.task.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(capability.provider.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(
            label = stringResource(capability.status.labelRes()),
            tone = capability.status.tone(),
        )
    }
}

internal fun AiTask.labelRes(): Int = when (this) {
    AiTask.ASK -> R.string.device_ai_task_ask
    AiTask.SUMMARIZE -> R.string.device_ai_task_summarize
    AiTask.REWRITE -> R.string.device_ai_task_rewrite
    AiTask.PROOFREAD -> R.string.device_ai_task_proofread
    AiTask.IMAGE_DESCRIPTION -> R.string.device_ai_task_image_description
    AiTask.IMAGE_QUESTION -> R.string.device_ai_task_image_question
    AiTask.ADVANCED_TRANSCRIPTION -> R.string.device_ai_task_advanced_transcription
    AiTask.BASIC_TRANSCRIPTION -> R.string.device_ai_task_basic_transcription
    AiTask.TEXT_RECOGNITION -> R.string.device_ai_task_text_recognition
    AiTask.TRANSLATION -> R.string.device_ai_task_translation
}

internal fun AiProvider.labelRes(): Int = when (this) {
    AiProvider.GEMINI_NANO -> R.string.provider_gemini_nano
    AiProvider.ML_KIT -> R.string.provider_ml_kit
    AiProvider.ANDROID_PLATFORM -> R.string.provider_android_platform
    AiProvider.ON_DEVICE_ALGORITHM -> R.string.provider_on_device_algorithm
    AiProvider.FAKE -> R.string.provider_fake
    AiProvider.NONE -> R.string.provider_none
}

internal fun AiCapabilityStatus.labelRes(): Int = when (this) {
    AiCapabilityStatus.AVAILABLE -> R.string.capability_on_device
    AiCapabilityStatus.DOWNLOADABLE -> R.string.capability_download_required
    AiCapabilityStatus.DOWNLOADING -> R.string.capability_downloading
    AiCapabilityStatus.UNSUPPORTED -> R.string.capability_unsupported
    AiCapabilityStatus.TEMPORARILY_UNAVAILABLE -> R.string.capability_temporarily_unavailable
    AiCapabilityStatus.ERROR -> R.string.capability_error
    AiCapabilityStatus.UNKNOWN -> R.string.capability_checking
}

internal fun AiCapabilityStatus.tone(): StatusTone = when (this) {
    AiCapabilityStatus.AVAILABLE -> StatusTone.Ready
    AiCapabilityStatus.DOWNLOADABLE,
    AiCapabilityStatus.DOWNLOADING,
    AiCapabilityStatus.TEMPORARILY_UNAVAILABLE,
    -> StatusTone.Pending
    AiCapabilityStatus.UNSUPPORTED, AiCapabilityStatus.ERROR -> StatusTone.Unsupported
    AiCapabilityStatus.UNKNOWN -> StatusTone.Neutral
}

@Preview(name = "Device AI - supported", showBackground = true)
@Composable
private fun DeviceAiSupportedPreview() {
    LocalAiTheme {
        DeviceAiContent(
            snapshot = DeviceAiSnapshot(capabilities = FakeCapabilityManager.supportedDevice()),
            onNavigateUp = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "Device AI - unsupported", showBackground = true)
@Composable
private fun DeviceAiUnsupportedPreview() {
    LocalAiTheme {
        DeviceAiContent(
            snapshot = DeviceAiSnapshot(capabilities = FakeCapabilityManager.unsupportedDevice()),
            onNavigateUp = {},
            onRefresh = {},
        )
    }
}
