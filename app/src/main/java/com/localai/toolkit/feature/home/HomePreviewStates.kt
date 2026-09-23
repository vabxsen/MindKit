package com.localai.toolkit.feature.home

import com.localai.toolkit.ai.fake.FakeCapabilityManager
import com.localai.toolkit.domain.model.DeviceAiSnapshot

/**
 * Ready-made Home states for Compose previews.
 *
 * Built from [FakeCapabilityManager]'s device profiles rather than hand-written literals,
 * so a preview cannot drift away from what the real mapping produces.
 */
internal object HomePreviewStates {

    val supported: HomeUiState =
        DeviceAiSnapshot(capabilities = FakeCapabilityManager.supportedDevice()).toUiState()

    val unsupported: HomeUiState =
        DeviceAiSnapshot(capabilities = FakeCapabilityManager.unsupportedDevice()).toUiState()

    val modelRequired: HomeUiState =
        DeviceAiSnapshot(capabilities = FakeCapabilityManager.downloadRequiredDevice()).toUiState()

    val checking: HomeUiState = DeviceAiSnapshot().toUiState()
}
