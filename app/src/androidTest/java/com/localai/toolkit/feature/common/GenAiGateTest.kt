package com.localai.toolkit.feature.common

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.domain.model.AiFailure
import org.junit.Rule
import org.junit.Test

/**
 * The gate every Gemini Nano tool sits behind.
 *
 * The most important assertion here is the negative one: on an unsupported device the
 * tool's own UI must not render at all, so there is no way to type a prompt into a screen
 * that cannot answer it.
 */
class GenAiGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val toolMarker = "TOOL CONTENT"

    @Test
    fun aReadyFeatureShowsTheTool() {
        setContent(GateState.READY)

        composeRule.onNodeWithText(toolMarker).assertIsDisplayed()
    }

    @Test
    fun anUnsupportedDeviceNeverRendersTheTool() {
        setContent(GateState.UNSUPPORTED)

        composeRule.onNodeWithText(toolMarker).assertDoesNotExist()
        composeRule.onNodeWithText("Not available on this device").assertIsDisplayed()
    }

    @Test
    fun anUnsupportedDeviceExplainsWhyRatherThanFailingSilently() {
        setContent(GateState.UNSUPPORTED)

        composeRule
            .onNodeWithText("On-device AI isn't available for this feature on your device.")
            .assertIsDisplayed()
    }

    @Test
    fun aMissingModelOffersADownloadInsteadOfTheTool() {
        var downloadRequested = false
        setContent(GateState.NEEDS_DOWNLOAD, onDownload = { downloadRequested = true })

        composeRule.onNodeWithText(toolMarker).assertDoesNotExist()
        composeRule.onNodeWithText("Download model").performClick()

        assertThat(downloadRequested).isTrue()
    }

    @Test
    fun aFailedDownloadIsReportedAboveTheRetryButton() {
        setContent(
            gateState = GateState.NEEDS_DOWNLOAD,
            downloadState = ModelDownloadState.Failed(AiFailure.DownloadFailed()),
        )

        composeRule.onNodeWithText("Model download failed.").assertIsDisplayed()
        composeRule.onNodeWithText("Download model").assertIsDisplayed()
    }

    @Test
    fun anInFlightDownloadShowsLabelledProgress() {
        setContent(
            gateState = GateState.DOWNLOADING,
            downloadState = ModelDownloadState.InProgress(downloadedBytes = 42, totalBytes = 100),
        )

        // Percentage rather than an unexplained spinner.
        composeRule.onNodeWithText("Downloading model 42%").assertIsDisplayed()
        composeRule.onNodeWithText(toolMarker).assertDoesNotExist()
    }

    @Test
    fun anUnresolvedCheckSaysWhatItIsWaitingFor() {
        setContent(GateState.CHECKING)

        composeRule.onNodeWithText("Checking AI availability…").assertIsDisplayed()
    }

    @Test
    fun aFailedCheckOffersARetry() {
        var retried = false
        setContent(GateState.ERROR, onRetryCheck = { retried = true })

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(retried).isTrue()
    }

    private fun setContent(
        gateState: GateState,
        downloadState: ModelDownloadState = ModelDownloadState.Idle,
        onDownload: () -> Unit = {},
        onRetryCheck: () -> Unit = {},
    ) {
        composeRule.setContent {
            LocalAiTheme {
                GenAiGate(
                    gateState = gateState,
                    downloadState = downloadState,
                    onDownload = onDownload,
                    onRetryCheck = onRetryCheck,
                ) { Text(toolMarker) }
            }
        }
    }
}
