package com.localai.toolkit.feature.home

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.fake.FakeCapabilityManager
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import com.localai.toolkit.domain.model.ToolId
import org.junit.Test

/**
 * The capability-to-Home mapping.
 *
 * This is the rule that decides what a user sees on the first screen, so it is pinned
 * down for each device profile rather than left to manual checking on hardware.
 */
class HomeCapabilityMappingTest {

    @Test
    fun `supported device reports ready`() {
        val state = snapshotOf(FakeCapabilityManager.supportedDevice()).toUiState()

        assertThat(state.readiness).isEqualTo(DeviceReadiness.READY)
        assertThat(state.tools.map { it.toolId }).containsExactlyElementsIn(ToolId.entries)
        assertThat(state.tools.all { it.enabled }).isTrue()
    }

    @Test
    fun `device without Gemini Nano reports limited but keeps ML Kit tools enabled`() {
        val state = snapshotOf(FakeCapabilityManager.unsupportedDevice()).toUiState()

        assertThat(state.readiness).isEqualTo(DeviceReadiness.LIMITED)

        val ocr = state.tools.single { it.toolId == ToolId.OCR }
        val translate = state.tools.single { it.toolId == ToolId.TRANSLATE }
        assertThat(ocr.enabled).isTrue()
        assertThat(translate.enabled).isTrue()

        val ask = state.tools.single { it.toolId == ToolId.ASK }
        assertThat(ask.enabled).isFalse()
        assertThat(ask.status).isEqualTo(AiCapabilityStatus.UNSUPPORTED)
    }

    @Test
    fun `download required device reports model required`() {
        val state = snapshotOf(FakeCapabilityManager.downloadRequiredDevice()).toUiState()

        assertThat(state.readiness).isEqualTo(DeviceReadiness.MODEL_REQUIRED)
    }

    @Test
    fun `empty snapshot reports checking rather than unsupported`() {
        // Regression guard: an unresolved snapshot must not be rendered as "this device
        // cannot do anything", which would be a false claim about the hardware.
        val state = DeviceAiSnapshot().toUiState()

        assertThat(state.readiness).isEqualTo(DeviceReadiness.CHECKING)
    }

    @Test
    fun `a tool with no capability requirement is always enabled`() {
        val state = snapshotOf(FakeCapabilityManager.unsupportedDevice()).toUiState()

        val developer = state.tools.single { it.toolId == ToolId.DEVELOPER }
        assertThat(ToolId.DEVELOPER.requiredTask).isNull()
        assertThat(developer.enabled).isTrue()
        assertThat(developer.status).isEqualTo(AiCapabilityStatus.AVAILABLE)
    }

    @Test
    fun `a downloading capability keeps its tool tappable`() {
        // The user should be able to open the tool and watch the download, not be locked
        // out of the screen while it runs.
        val capabilities = FakeCapabilityManager.supportedDevice() + mapOf(
            AiTask.SUMMARIZE to AiCapability(
                task = AiTask.SUMMARIZE,
                status = AiCapabilityStatus.DOWNLOADING,
                provider = AiProvider.GEMINI_NANO,
            ),
        )

        val state = snapshotOf(capabilities).toUiState()

        val summarize = state.tools.single { it.toolId == ToolId.SUMMARIZE }
        assertThat(summarize.enabled).isTrue()
        assertThat(summarize.status).isEqualTo(AiCapabilityStatus.DOWNLOADING)
    }

    private fun snapshotOf(capabilities: Map<AiTask, AiCapability>) =
        DeviceAiSnapshot(capabilities = capabilities, lastCheckedAtEpochMillis = 0L)
}
