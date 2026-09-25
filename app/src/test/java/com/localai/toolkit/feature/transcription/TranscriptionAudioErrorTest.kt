package com.localai.toolkit.feature.transcription

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TranscriptionAudioErrorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `active local download still offers Check status`() = verifyDownloadCheck(AiCapabilityStatus.DOWNLOADABLE)
    @Test fun `downloading status with local progress still offers Check status`() = verifyDownloadCheck(AiCapabilityStatus.DOWNLOADING)
    @Test fun `active download with an older ready status still offers Check status`() = verifyDownloadCheck(AiCapabilityStatus.AVAILABLE)

    @Test fun `failed post-download check offers Check status without another Download`() {
        var checks = 0
        showState(TranscriptionUiState(
            basicStatus = AiCapabilityStatus.AVAILABLE, supportsFileInput = true,
            downloadState = ModelDownloadState.Completed,
            downloadCheckFailure = AiFailure.Unknown("private check detail"),
        ), onRetry = { checks++ })
        compose.onNodeWithText("Check status").performScrollTo().assertIsEnabled().performClick()
        assertThat(checks).isEqualTo(1)
        compose.onNodeWithText("Download model").assertDoesNotExist()
        compose.onNodeWithText("private check detail", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Choose an audio file").performScrollTo().assertIsEnabled()
    }

    private fun verifyDownloadCheck(status: AiCapabilityStatus) {
        var checks = 0
        var downloads = 0
        showState(TranscriptionUiState(
            basicStatus = status, downloadState = ModelDownloadState.InProgress(1, 2),
        ), onRetry = { checks++ }, onDownload = { downloads++ })
        compose.onNodeWithText("Check status").performScrollTo().assertIsEnabled().performClick()
        assertThat(checks).isEqualTo(1)
        assertThat(downloads).isEqualTo(0)
    }

    @Test fun `invalid audio explains recovery and another file can still be chosen`() {
        compose.setContent { LocalAiTheme {
            TranscriptionContent(
                state = TranscriptionUiState(basicStatus = AiCapabilityStatus.AVAILABLE,
                    supportsFileInput = true, failure = AiFailure.InvalidAudio("private decoder detail")),
                verboseErrors = false, onModeChange = {}, onStartRecording = {}, onStopRecording = {},
                onFileSelected = {}, onMicrophoneDenied = {}, onDownload = {}, onRetryCheck = {},
                onSave = {}, onClear = {}, onSendTo = {}, onNavigateUp = {},
            )
        } }
        compose.onNodeWithText("This audio file could not be read or decoded. Choose another audio file.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("private decoder detail", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Choose an audio file").performScrollTo().assertIsEnabled().performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = shadowOf(app).nextStartedActivity
        assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT)
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).asList().containsExactly("audio/*")
    }

    @Test fun `availability failure cannot hide Stop during recording`() {
        var stops = 0
        showState(TranscriptionUiState(
            basicStatus = AiCapabilityStatus.ERROR, isRecording = true,
            failure = AiFailure.Unknown("availability failed"),
        ), onStop = { stops++ })
        compose.onNodeWithText("Stop recording").performScrollTo().assertIsEnabled().performClick()
        assertThat(stops).isEqualTo(1)
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test fun `stale download failure cannot hide Cancel during file transcription`() {
        var cancels = 0
        showState(TranscriptionUiState(
            basicStatus = AiCapabilityStatus.AVAILABLE, supportsFileInput = true,
            isTranscribingFile = true,
            downloadState = ModelDownloadState.Failed(AiFailure.DownloadFailed("old attempt")),
        ), onClear = { cancels++ })
        compose.onNodeWithText("Cancel").performScrollTo().assertIsEnabled().performClick()
        assertThat(cancels).isEqualTo(1)
        compose.onNodeWithText("Download model").assertDoesNotExist()
    }

    private fun showState(
        state: TranscriptionUiState,
        onStop: () -> Unit = {},
        onClear: () -> Unit = {},
        onRetry: () -> Unit = {},
        onDownload: () -> Unit = {},
    ) {
        compose.setContent { LocalAiTheme {
            TranscriptionContent(
                state = state, verboseErrors = false,
                onModeChange = {}, onStartRecording = {}, onStopRecording = onStop,
                onFileSelected = {}, onMicrophoneDenied = {}, onDownload = onDownload, onRetryCheck = onRetry,
                onSave = {}, onClear = onClear, onSendTo = {}, onNavigateUp = {},
            )
        } }
    }
}
