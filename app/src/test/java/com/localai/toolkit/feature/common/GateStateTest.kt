package com.localai.toolkit.feature.common

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import org.junit.Test

/**
 * The rule that decides whether a GenAI tool shows itself, a download prompt or an
 * explanation.
 *
 * This is the single place that can accidentally show a working tool on a device that
 * cannot run it, so each combination is pinned.
 */
class GateStateTest {

    @Test
    fun `available capability shows the tool`() {
        assertThat(gate(AiCapabilityStatus.AVAILABLE)).isEqualTo(GateState.READY)
    }

    @Test
    fun `unsupported capability explains rather than showing the tool`() {
        assertThat(gate(AiCapabilityStatus.UNSUPPORTED)).isEqualTo(GateState.UNSUPPORTED)
    }

    @Test
    fun `downloadable capability offers a download`() {
        assertThat(gate(AiCapabilityStatus.DOWNLOADABLE)).isEqualTo(GateState.NEEDS_DOWNLOAD)
    }

    @Test
    fun `an unresolved capability shows checking, never unsupported`() {
        // Reporting UNKNOWN as unsupported would tell users their hardware cannot do
        // something before the app has actually asked.
        assertThat(gate(AiCapabilityStatus.UNKNOWN)).isEqualTo(GateState.CHECKING)
    }

    @Test
    fun `an in-flight download takes priority over the cached capability`() {
        // The capability snapshot still says "downloadable" until the download finishes
        // and it is re-checked, so progress has to win.
        val state = gateStateOf(
            capability(AiCapabilityStatus.DOWNLOADABLE),
            ModelDownloadState.InProgress(downloadedBytes = 10, totalBytes = 100),
        )

        assertThat(state).isEqualTo(GateState.DOWNLOADING)
    }

    @Test
    fun `a started download shows as downloading`() {
        val state = gateStateOf(
            capability(AiCapabilityStatus.DOWNLOADABLE),
            ModelDownloadState.Started(totalBytes = 100),
        )

        assertThat(state).isEqualTo(GateState.DOWNLOADING)
    }

    @Test
    fun `a failed download falls back to offering the download again`() {
        val state = gateStateOf(
            capability(AiCapabilityStatus.DOWNLOADABLE),
            ModelDownloadState.Failed(AiFailure.DownloadFailed()),
        )

        assertThat(state).isEqualTo(GateState.NEEDS_DOWNLOAD)
    }

    @Test
    fun `a capability reported as downloading shows progress without a local download`() {
        // Another app, or the system, may already be fetching the same feature model.
        assertThat(gate(AiCapabilityStatus.DOWNLOADING)).isEqualTo(GateState.DOWNLOADING)
    }

    @Test
    fun `a failed check offers a retry`() {
        assertThat(gate(AiCapabilityStatus.ERROR)).isEqualTo(GateState.ERROR)
        assertThat(gate(AiCapabilityStatus.TEMPORARILY_UNAVAILABLE)).isEqualTo(GateState.ERROR)
    }

    @Test
    fun `download progress reports a usable fraction`() {
        val progress = ModelDownloadState.InProgress(downloadedBytes = 25, totalBytes = 100)

        assertThat(progress.fraction).isEqualTo(0.25f)
    }

    @Test
    fun `download progress with no reported total has no fraction`() {
        // ML Kit's progress callback does not always supply a total; showing a made-up
        // percentage would be worse than showing an indeterminate bar.
        val progress = ModelDownloadState.InProgress(downloadedBytes = 25, totalBytes = null)

        assertThat(progress.fraction).isNull()
    }

    private fun gate(status: AiCapabilityStatus): GateState =
        gateStateOf(capability(status), ModelDownloadState.Idle)

    @Test fun `failed download overrides a stale downloading status`() {
        assertThat(gateStateOf(
            capability(AiCapabilityStatus.DOWNLOADING),
            ModelDownloadState.Failed(AiFailure.DownloadFailed()),
        )).isEqualTo(GateState.NEEDS_DOWNLOAD)
    }

    @Test fun `available and unsupported status still win over a past failure`() {
        val failed = ModelDownloadState.Failed(AiFailure.DownloadFailed())
        assertThat(gateStateOf(capability(AiCapabilityStatus.AVAILABLE), failed)).isEqualTo(GateState.READY)
        assertThat(gateStateOf(capability(AiCapabilityStatus.UNSUPPORTED), failed)).isEqualTo(GateState.UNSUPPORTED)
    }

    private fun capability(status: AiCapabilityStatus) =
        AiCapability(AiTask.SUMMARIZE, status, AiProvider.GEMINI_NANO)

    @Test fun `failed check during local download preserves progress with an error state`() {
        assertThat(gateStateOf(capability(AiCapabilityStatus.ERROR), ModelDownloadState.InProgress(20, 100)))
            .isEqualTo(GateState.DOWNLOADING_CHECK_FAILED)
    }

    @Test fun `temporary check unavailability during download can be checked again`() {
        assertThat(gateStateOf(capability(AiCapabilityStatus.TEMPORARILY_UNAVAILABLE), ModelDownloadState.Started(100)))
            .isEqualTo(GateState.DOWNLOADING_CHECK_FAILED)
    }
}
