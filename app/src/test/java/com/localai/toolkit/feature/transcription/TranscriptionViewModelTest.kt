package com.localai.toolkit.feature.transcription

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.gemini.TranscriptChunk
import com.localai.toolkit.ai.gemini.TranscriptionEngine
import com.localai.toolkit.ai.gemini.TranscriptionMode
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val engine = TestSpeechEngine()
    private val handoff = ToolHandoff()
    private val capabilities = TestCapabilities()
    private val audio = Uri.parse("content://test/audio")
    private fun model(history: MemoryHistory = MemoryHistory()) = main.own(TranscriptionViewModel(
        engine, history, handoff, capabilities, MemorySettings(),
    ))

    @Test fun `clearing history lets the same transcript be saved without importing again`() = runTest {
        val history = MemoryHistory()
        val vm = model(history)
        runCurrent()
        vm.onFileSelected(audio)
        runCurrent()
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        history.deleteAll()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        vm.onSave()
        runCurrent()
        assertThat(history.items.value.single().output).isEqualTo("File transcript")
        assertThat(engine.files).containsExactly(audio)
    }

    @Test fun `shared audio waits for availability before starting`() = runTest {
        engine.availability = CompletableDeferred()
        handoff.send(HandoffPayload(ToolId.TRANSCRIBE, audioUri = audio))
        val vm = model()
        runCurrent()
        assertThat(engine.files).isEmpty()
        engine.availability.complete(Unit)
        runCurrent()
        assertThat(engine.files).containsExactly(audio)
        assertThat(vm.uiState.value.transcript).isEqualTo("File transcript")
        assertThat(vm.uiState.value.isBusy).isFalse()
    }

    @Test fun `recording supersedes queued shared audio even when another mode later supports files`() = runTest {
        engine.basicFileSupport = false
        engine.advancedStatus = AiCapabilityStatus.DOWNLOADABLE
        engine.microphoneCompletes = true
        handoff.send(HandoffPayload(ToolId.TRANSCRIBE, audioUri = audio))
        val vm = model()
        runCurrent()
        assertThat(engine.files).isEmpty()
        vm.onStartRecording()
        runCurrent()
        assertThat(vm.uiState.value.transcript).isEqualTo("Heard speech")

        engine.advancedStatus = AiCapabilityStatus.AVAILABLE
        vm.onModeChange(TranscriptionMode.ADVANCED)
        runCurrent()
        assertThat(engine.files).isEmpty()
        assertThat(vm.uiState.value.transcript).isEqualTo("Heard speech")
        assertThat(vm.uiState.value.resultMode).isEqualTo(TranscriptionMode.BASIC)
    }

    @Test fun `availability Retry checks status instead of requeueing the last file forever`() = runTest {
        val vm = model()
        runCurrent()
        vm.onFileSelected(audio)
        runCurrent()
        engine.statusFails = true
        vm.refreshAvailability()
        runCurrent()
        assertThat(vm.uiState.value.currentStatus).isEqualTo(AiCapabilityStatus.ERROR)

        engine.statusFails = false
        vm.onRetry()
        runCurrent()
        assertThat(vm.uiState.value.currentStatus).isEqualTo(AiCapabilityStatus.AVAILABLE)
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(engine.files).containsExactly(audio)
        assertThat(vm.uiState.value.transcript).isEqualTo("File transcript")
    }

    @Test fun `new queued file wins over an older failed file when status Retry recovers`() = runTest {
        val vm = model()
        runCurrent()
        engine.fileFails = true
        vm.onFileSelected(audio)
        runCurrent()
        engine.statusFails = true
        vm.refreshAvailability()
        runCurrent()
        val replacement = Uri.parse("content://test/replacement")
        vm.onFileSelected(replacement)

        engine.statusFails = false
        engine.fileFails = false
        vm.onRetry()
        runCurrent()
        assertThat(engine.files).containsExactly(audio, replacement).inOrder()
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(vm.uiState.value.transcript).isEqualTo("File transcript")
    }

    @Test fun `denied and disabled recording do not consume queued audio`() = runTest {
        engine.basicStatus = AiCapabilityStatus.DOWNLOADABLE
        handoff.send(HandoffPayload(ToolId.TRANSCRIBE, audioUri = audio))
        val vm = model()
        runCurrent()
        vm.onMicrophoneDenied()
        vm.onStartRecording()
        runCurrent()
        assertThat(engine.recordingStarts).isEqualTo(0)

        engine.basicStatus = AiCapabilityStatus.AVAILABLE
        vm.refreshAvailability()
        runCurrent()
        assertThat(engine.files).containsExactly(audio)
    }

    @Test fun `Clear discards queued audio before availability recovers`() = runTest {
        engine.basicStatus = AiCapabilityStatus.DOWNLOADABLE
        handoff.send(HandoffPayload(ToolId.TRANSCRIBE, audioUri = audio))
        val vm = model()
        runCurrent()
        vm.onClear()
        engine.basicStatus = AiCapabilityStatus.AVAILABLE
        vm.refreshAvailability()
        runCurrent()
        assertThat(engine.files).isEmpty()
        assertThat(vm.uiState.value.transcript).isEmpty()
    }

    @Test fun `late availability success does not erase a newer recording failure`() = runTest {
        val vm = model()
        runCurrent()
        engine.availability = CompletableDeferred()
        vm.refreshAvailability()
        runCurrent()
        engine.microphoneStreamFails = true
        vm.onStartRecording()
        runCurrent()
        val failure = vm.uiState.value.failure
        assertThat(failure).isNotNull()

        engine.availability.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.failure).isSameInstanceAs(failure)
        assertThat(vm.uiState.value.transcript).isEqualTo("Heard speech")
    }

    @Test fun `retry reruns the failed audio file`() = runTest {
        val vm = model()
        runCurrent()
        engine.fileFails = true
        vm.onFileSelected(audio)
        runCurrent()
        assertThat(vm.uiState.value.failure).isNotNull()
        engine.fileFails = false
        vm.onRetry()
        runCurrent()
        assertThat(engine.files).containsExactly(audio, audio)
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(vm.uiState.value.transcript).isEqualTo("File transcript")
    }

    @Test fun `stop is bounded even when recognizer stop never returns`() = runTest {
        val vm = model()
        runCurrent()
        vm.onStartRecording()
        runCurrent()
        vm.onStopRecording()
        vm.onStopRecording()
        runCurrent()
        assertThat(engine.stopCalls).isEqualTo(1)
        advanceTimeBy(2_001)
        runCurrent()
        assertThat(vm.uiState.value.isRecording).isFalse()
        assertThat(vm.uiState.value.transcript).isEqualTo("Heard speech")
        assertThat(vm.uiState.value.canStart).isTrue()
    }

    @Test fun `leaving screen releases microphone and preserves text`() = runTest {
        val vm = model()
        runCurrent()
        vm.onStartRecording()
        runCurrent()
        vm.onScreenHidden()
        runCurrent()
        assertThat(engine.releaseCalls).isEqualTo(1)
        assertThat(vm.uiState.value.isRecording).isFalse()
        assertThat(vm.uiState.value.transcript).isEqualTo("Heard speech")
    }

    @Test fun `clear cancels file and retry cannot resurrect it`() = runTest {
        val vm = model()
        runCurrent()
        engine.holdFile = true
        vm.onFileSelected(audio)
        runCurrent()
        assertThat(vm.uiState.value.isTranscribingFile).isTrue()
        vm.onClear()
        vm.onRetry()
        runCurrent()
        assertThat(vm.uiState.value.isBusy).isFalse()
        assertThat(vm.uiState.value.transcript).isEmpty()
        assertThat(engine.files).containsExactly(audio)
    }

    @Test fun `download taps coalesce and pending audio starts after completion`() = runTest {
        engine.basicStatus = AiCapabilityStatus.DOWNLOADABLE
        handoff.send(HandoffPayload(ToolId.TRANSCRIBE, audioUri = audio))
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        vm.onDownloadModel()
        runCurrent()
        assertThat(engine.downloadCalls).isEqualTo(1)
        assertThat(engine.files).isEmpty()
        engine.finishDownload.complete(Unit)
        runCurrent()
        assertThat(engine.files).containsExactly(audio)
        assertThat(capabilities.refreshedTasks).containsExactly(AiTask.BASIC_TRANSCRIPTION)
    }

    @Test fun `mode explicitly selected during first check is not overwritten`() = runTest {
        engine.availability = CompletableDeferred()
        engine.advancedStatus = AiCapabilityStatus.AVAILABLE
        val vm = model()
        runCurrent()
        vm.onModeChange(TranscriptionMode.BASIC)
        engine.availability.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.mode).isEqualTo(TranscriptionMode.BASIC)
    }

    @Test fun `downloads follow their mode and failed download Retry downloads again`() = runTest {
        engine.basicStatus = AiCapabilityStatus.DOWNLOADABLE
        engine.advancedStatus = AiCapabilityStatus.DOWNLOADABLE
        engine.downloadFails = true
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        vm.onModeChange(TranscriptionMode.ADVANCED)
        runCurrent()
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Idle)
        engine.finishDownload.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Idle)
        vm.onModeChange(TranscriptionMode.BASIC)
        runCurrent()
        assertThat(vm.uiState.value.downloadState).isInstanceOf(ModelDownloadState.Failed::class.java)
        engine.downloadFails = false
        vm.onRetry()
        runCurrent()
        assertThat(engine.downloadCalls).isEqualTo(2)
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
        assertThat(vm.uiState.value.failure).isNull()
    }

    @Test fun `switching back to an active download restores its progress and coalesces Retry`() = runTest {
        engine.basicStatus = AiCapabilityStatus.DOWNLOADABLE
        engine.advancedStatus = AiCapabilityStatus.DOWNLOADABLE
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        vm.onModeChange(TranscriptionMode.ADVANCED)
        runCurrent()
        vm.onModeChange(TranscriptionMode.BASIC)
        runCurrent()
        assertThat(vm.uiState.value.downloadState).isInstanceOf(ModelDownloadState.Started::class.java)
        vm.onDownloadModel()
        runCurrent()
        assertThat(engine.downloadCalls).isEqualTo(1)
    }

    @Test fun `availability exceptions leave a retryable screen and successful Retry clears error`() = runTest {
        engine.statusFails = true
        val vm = model()
        runCurrent()
        assertThat(vm.uiState.value.currentStatus).isEqualTo(AiCapabilityStatus.ERROR)
        engine.statusFails = false
        vm.onRetry()
        runCurrent()
        assertThat(vm.uiState.value.currentStatus).isEqualTo(AiCapabilityStatus.AVAILABLE)
        assertThat(vm.uiState.value.failure).isNull()
    }

    @Test fun `microphone setup failure clears recording state and allows another attempt`() = runTest {
        val vm = model()
        runCurrent()
        engine.microphoneFactoryFails = true
        vm.onStartRecording()
        runCurrent()
        assertThat(vm.uiState.value.isBusy).isFalse()
        assertThat(vm.uiState.value.failure).isInstanceOf(AiFailure.Unknown::class.java)
        engine.microphoneFactoryFails = false
        vm.onStartRecording()
        runCurrent()
        assertThat(vm.uiState.value.isRecording).isTrue()
        assertThat(vm.uiState.value.failure).isNull()
    }

    @Test fun `file setup failure clears busy state and Retry reopens the same file`() = runTest {
        val vm = model()
        runCurrent()
        engine.fileFactoryFails = true
        vm.onFileSelected(audio)
        runCurrent()
        assertThat(vm.uiState.value.isBusy).isFalse()
        assertThat(vm.uiState.value.failure).isInstanceOf(AiFailure.Unknown::class.java)
        engine.fileFactoryFails = false
        vm.onRetry()
        runCurrent()
        assertThat(vm.uiState.value.transcript).isEqualTo("File transcript")
        assertThat(vm.uiState.value.failure).isNull()
    }

    @Test fun `unexpected stream failure preserves captured text and ends recording`() = runTest {
        val vm = model()
        runCurrent()
        engine.microphoneStreamFails = true
        vm.onStartRecording()
        runCurrent()
        assertThat(vm.uiState.value.isBusy).isFalse()
        assertThat(vm.uiState.value.transcript).isEqualTo("Heard speech")
        assertThat(vm.uiState.value.failure).isInstanceOf(AiFailure.Unknown::class.java)
        assertThat(engine.microphoneCollectors).isEqualTo(0)
    }

    @Test fun `Stop failure still cancels recording and keeps captured text`() = runTest {
        val vm = model()
        runCurrent()
        engine.stopFails = true
        vm.onStartRecording()
        runCurrent()
        vm.onStopRecording()
        runCurrent()
        assertThat(vm.uiState.value.isBusy).isFalse()
        assertThat(vm.uiState.value.transcript).isEqualTo("Heard speech")
        assertThat(vm.uiState.value.failure).isInstanceOf(AiFailure.Unknown::class.java)
        assertThat(engine.microphoneCollectors).isEqualTo(0)
    }

    @Test fun `completed response closes upstream before enabling another recording`() = runTest {
        val vm = model()
        runCurrent()
        engine.microphoneCompletes = true
        vm.onStartRecording()
        runCurrent()
        assertThat(vm.uiState.value.isBusy).isFalse()
        assertThat(engine.microphoneCollectors).isEqualTo(0)
        assertThat(vm.uiState.value.failure).isNull()
    }

    @Test fun `Clear cancels recording without showing a cancellation error`() = runTest {
        val vm = model()
        runCurrent()
        vm.onStartRecording()
        runCurrent()
        vm.onClear()
        runCurrent()
        assertThat(engine.microphoneCollectors).isEqualTo(0)
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(vm.uiState.value.transcript).isEmpty()
    }

    @Test fun `rapid Clear and Record waits for old cleanup and skips cancelled queued recordings`() = runTest {
        val vm = model()
        runCurrent()
        val cleanup = CompletableDeferred<Unit>()
        engine.microphoneCleanup = cleanup
        vm.onStartRecording()
        runCurrent()
        engine.microphoneCleanup = null
        vm.onClear()
        vm.onStartRecording()
        runCurrent()
        vm.onClear()
        vm.onStartRecording()
        runCurrent()
        try {
            assertThat(engine.recordingStarts).isEqualTo(1)
            assertThat(engine.microphoneCollectors).isEqualTo(1)
            cleanup.complete(Unit)
            runCurrent()
            assertThat(engine.recordingStarts).isEqualTo(2)
            assertThat(engine.microphoneCollectors).isEqualTo(1)
            assertThat(vm.uiState.value.isRecording).isTrue()
            assertThat(vm.uiState.value.failure).isNull()
        } finally {
            cleanup.complete(Unit)
            vm.onClear()
            runCurrent()
        }
    }

    private class TestSpeechEngine : TranscriptionEngine {
        var availability = CompletableDeferred(Unit)
        var basicStatus = AiCapabilityStatus.AVAILABLE
        var advancedStatus = AiCapabilityStatus.UNSUPPORTED
        var basicFileSupport = true
        var fileFails = false
        var holdFile = false
        var releaseCalls = 0
        var stopCalls = 0
        var downloadCalls = 0
        var downloadFails = false
        var statusFails = false
        var microphoneFactoryFails = false
        var fileFactoryFails = false
        var microphoneStreamFails = false
        var microphoneCompletes = false
        var stopFails = false
        var microphoneCollectors = 0
        var recordingStarts = 0
        var microphoneCleanup: CompletableDeferred<Unit>? = null
        val files = mutableListOf<Uri>()
        val finishDownload = CompletableDeferred<Unit>()
        override suspend fun status(mode: TranscriptionMode): AiCapabilityStatus {
            availability.await()
            if (statusFails) error("status unavailable")
            return if (mode == TranscriptionMode.BASIC) basicStatus else advancedStatus
        }
        override suspend fun provider(mode: TranscriptionMode) = AiProvider.GEMINI_NANO
        override suspend fun supportsFileInput(mode: TranscriptionMode) =
            mode == TranscriptionMode.ADVANCED || basicFileSupport
        override fun downloadModel(mode: TranscriptionMode): Flow<ModelDownloadState> = flow {
            downloadCalls++
            emit(ModelDownloadState.Started(null))
            finishDownload.await()
            if (downloadFails) throw IllegalStateException("download interrupted")
            basicStatus = AiCapabilityStatus.AVAILABLE
            emit(ModelDownloadState.Completed)
        }
        override fun transcribeMicrophone(mode: TranscriptionMode): Flow<TranscriptChunk> {
            if (microphoneFactoryFails) error("microphone setup failed")
            return flow {
                recordingStarts++
                microphoneCollectors++
                val cleanup = microphoneCleanup
                try {
                    emit(TranscriptChunk.Partial("Heard speech"))
                    if (microphoneStreamFails) error("speech service disconnected")
                    if (microphoneCompletes) emit(TranscriptChunk.Completed)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup?.await() }
                    microphoneCollectors--
                }
            }
        }
        override fun transcribeFile(uri: Uri, mode: TranscriptionMode): Flow<TranscriptChunk> {
            if (fileFactoryFails) error("audio setup failed")
            return flow {
                files += uri
                if (fileFails) throw AiException(AiFailure.Unsupported("test failure"))
                if (holdFile) awaitCancellation()
                emit(TranscriptChunk.Final("File transcript"))
                emit(TranscriptChunk.Completed)
            }
        }
        override suspend fun stop() {
            stopCalls++
            if (stopFails) error("stop failed")
            awaitCancellation()
        }
        override fun release() { releaseCalls++ }
    }
}
