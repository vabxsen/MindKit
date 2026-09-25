package com.localai.toolkit.feature

import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.feature.common.GateState
import com.localai.toolkit.feature.common.GenAiGate
import com.localai.toolkit.feature.common.HistorySaveFeedback
import com.localai.toolkit.feature.ask.AskContent
import com.localai.toolkit.feature.ask.AskMessage
import com.localai.toolkit.feature.ask.AskUiState
import com.localai.toolkit.ai.engine.AskRole
import com.localai.toolkit.feature.image.ImageContent
import com.localai.toolkit.feature.image.ImageUiState
import com.localai.toolkit.feature.transcription.TranscriptionContent
import com.localai.toolkit.feature.transcription.TranscriptionUiState
import com.localai.toolkit.feature.settings.ModelsContent
import com.localai.toolkit.feature.settings.ModelsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Real Compose semantics interactions on the JVM, not just ViewModel calls. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FeatureControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `image setup error shows friendly feedback and working Retry`() {
        var retried = 0
        imageContent(ImageUiState(
            preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
            mode = com.localai.toolkit.feature.image.ImageMode.ASK,
            question = "What is here?",
            failure = AiFailure.Unknown("Private setup failure"),
        ), onRun = { retried++ })
        compose.onNodeWithText("Private setup failure", substring = true).assertDoesNotExist()
        compose.onNodeWithText("What is here?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        assertThat(retried).isEqualTo(1)
    }

    @Test fun `loading an image exposes a functioning cancel button`() {
        var cleared = 0
        imageContent(ImageUiState(isLoadingImage = true), onClear = { cleared++ })
        compose.onNodeWithText("Loading image…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(cleared).isEqualTo(1)
    }

    @Test fun `image inference exposes stop and disables duplicate run`() {
        var stopped = 0
        imageContent(ImageUiState(
            preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), isWorking = true,
        ), onStop = { stopped++ })
        compose.onNodeWithText("Describe image").assertIsNotEnabled()
        compose.onNodeWithText("Stop").performScrollTo().performClick()
        assertThat(stopped).isEqualTo(1)
    }

    @Test fun `transcribing file can be canceled before any text exists`() {
        var canceled = 0
        transcriptionContent(TranscriptionUiState(
            basicStatus = AiCapabilityStatus.AVAILABLE,
            supportsFileInput = true,
            isTranscribingFile = true,
        ), onClear = { canceled++ })
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertThat(canceled).isEqualTo(1)
    }

    @Test fun `transcription check failure exposes functioning retry`() {
        var retried = 0
        transcriptionContent(TranscriptionUiState(basicStatus = AiCapabilityStatus.ERROR),
            onRetry = { retried++ })
        compose.onNodeWithText("Retry").performClick()
        assertThat(retried).isEqualTo(1)
    }

    @Test fun `external model download exposes a status refresh action`() {
        var refreshed = 0
        compose.setContent {
            LocalAiTheme {
                GenAiGate(GateState.DOWNLOADING, ModelDownloadState.Idle,
                    onDownload = {}, onRetryCheck = { refreshed++ }) { Text("Tool") }
            }
        }
        compose.onNodeWithText("Tool").assertDoesNotExist()
        compose.onNodeWithText("Check status").performClick()
        assertThat(refreshed).isEqualTo(1)
    }

    @Test fun `failed download has a functioning retry download action`() {
        var downloaded = 0
        compose.setContent {
            LocalAiTheme {
                GenAiGate(GateState.NEEDS_DOWNLOAD,
                    ModelDownloadState.Failed(AiFailure.DownloadFailed()),
                    onDownload = { downloaded++ }, onRetryCheck = {}) { Text("Tool") }
            }
        }
        compose.onNodeWithText("Model download failed.").assertIsDisplayed()
        compose.onNodeWithText("Download model").performClick()
        assertThat(downloaded).isEqualTo(1)
    }

    @Test fun `failed status check during download shows progress error and working check button`() {
        var checked = 0
        var downloads = 0
        compose.setContent {
            LocalAiTheme {
                GenAiGate(GateState.DOWNLOADING_CHECK_FAILED, ModelDownloadState.InProgress(30, 100),
                    onDownload = { downloads++ }, onRetryCheck = { checked++ }) { Text("Tool") }
            }
        }
        compose.onNodeWithText("Downloading model 30%").assertIsDisplayed()
        compose.onNodeWithText("The availability check didn't complete.").assertIsDisplayed()
        compose.onNodeWithText("Check status").performClick()
        assertThat(checked).isEqualTo(1)
        assertThat(downloads).isEqualTo(0)
        compose.onNodeWithText("Tool").assertDoesNotExist()
    }

    @Test fun `saving with history disabled shows the reason in the screen snackbar`() {
        val feedback = Channel<HistorySaveFeedback>(Channel.BUFFERED)
        imageContent(ImageUiState(
            preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), output = "Description",
        ), onSave = { feedback.trySend(HistorySaveFeedback.DISABLED) }, feedback = feedback.receiveAsFlow())
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("History is turned off, so this was not saved").assertIsDisplayed()
    }

    @Test fun `models listing error has a working Retry control`() {
        var retries = 0
        compose.setContent {
            LocalAiTheme {
                ModelsContent(
                    state = ModelsUiState(isLoading = false, failure = AiFailure.Unknown()),
                    onQueryChange = {}, onDownload = {}, onDelete = {}, onNavigateUp = {},
                    onRetry = { retries++ },
                )
            }
        }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test fun `streaming image answer cannot be saved as a completed result`() {
        imageContent(ImageUiState(
            preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
            output = "Partial", isWorking = true, isStreaming = true,
        ))
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `regenerate button passes the id of the answer that was tapped`() {
        var selected: Long? = null
        compose.setContent {
            LocalAiTheme {
                AskContent(
                    state = AskUiState(messages = listOf(
                        AskMessage(0, AskRole.USER, "First"), AskMessage(1, AskRole.MODEL, "First answer"),
                        AskMessage(2, AskRole.USER, "Second"), AskMessage(3, AskRole.MODEL, "Second answer"),
                    )),
                    capability = AiCapability(AiTask.ASK, AiCapabilityStatus.AVAILABLE, AiProvider.FAKE),
                    downloadState = ModelDownloadState.Idle, verboseErrors = false,
                    onInputChange = {}, onSend = {}, onStop = {}, onRetry = { selected = it },
                    onNewConversation = {}, onSave = {}, onDownload = {}, onRetryCheck = {}, onNavigateUp = {},
                )
            }
        }
        compose.onAllNodesWithText("Regenerate")[0].performClick()
        assertThat(selected).isEqualTo(1)
    }

    @Test fun `externally downloading speech model offers status refresh`() {
        var refreshed = 0
        transcriptionContent(TranscriptionUiState(basicStatus = AiCapabilityStatus.DOWNLOADING),
            onRetry = { refreshed++ })
        compose.onNodeWithText("Check status").performScrollTo().performClick()
        assertThat(refreshed).isEqualTo(1)
    }

    @Test fun `failed speech download is not hidden behind stale downloading status`() {
        var retries = 0
        transcriptionContent(TranscriptionUiState(
            basicStatus = AiCapabilityStatus.DOWNLOADING,
            downloadState = ModelDownloadState.Failed(AiFailure.DownloadFailed()),
            failure = AiFailure.DownloadFailed(),
        ), onDownload = { retries++ })
        compose.onNodeWithText("Download model").performScrollTo().assertIsDisplayed().performClick()
        assertThat(retries).isEqualTo(1)
    }

    private fun imageContent(
        state: ImageUiState, onClear: () -> Unit = {}, onStop: () -> Unit = {},
        onSave: () -> Unit = {}, feedback: Flow<HistorySaveFeedback> = emptyFlow(),
        onRun: () -> Unit = {},
    ) {
        compose.setContent {
            LocalAiTheme {
                ImageContent(
                    state = state,
                    capability = AiCapability(AiTask.IMAGE_DESCRIPTION, AiCapabilityStatus.AVAILABLE, AiProvider.FAKE),
                    downloadState = ModelDownloadState.Idle, verboseErrors = false,
                    onImageSelected = {}, onModeChange = {}, onQuestionChange = {},
                    onRun = onRun, onStop = onStop, onSave = onSave, onClear = onClear,
                    saveFeedback = feedback,
                    onDownload = {}, onRetryCheck = {}, onRunOcr = {}, onSendTextTo = {},
                    onNavigateUp = {},
                )
            }
        }
    }

    private fun transcriptionContent(
        state: TranscriptionUiState, onClear: () -> Unit = {}, onRetry: () -> Unit = {},
        onDownload: () -> Unit = {},
    ) {
        compose.setContent {
            LocalAiTheme {
                TranscriptionContent(
                    state = state, verboseErrors = false, onModeChange = {}, onStartRecording = {},
                    onStopRecording = {}, onFileSelected = {}, onMicrophoneDenied = {},
                    onDownload = onDownload, onRetryCheck = onRetry, onSave = {}, onClear = onClear,
                    onSendTo = {}, onNavigateUp = {},
                )
            }
        }
    }
}
