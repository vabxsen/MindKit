package com.localai.toolkit.feature.transcription

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.gemini.TranscriptChunk
import com.localai.toolkit.ai.gemini.TranscriptionEngine
import com.localai.toolkit.ai.gemini.TranscriptionMode
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechDownloadRecoveryTest {
    @get:Rule val main = MainDispatcherRule()
    private val engine = Speech()
    private var checkFails = false
    private var checks = 0
    private var checkGate: CompletableDeferred<Unit>? = null
    private val capabilities = object : DeviceAiCapabilityManager by TestCapabilities() {
        override suspend fun refresh(task: AiTask) {
            checks++
            checkGate?.await()
            if (checkFails) error("status refresh failed")
        }
    }
    private fun model() = main.own(TranscriptionViewModel(
        engine, MemoryHistory(), ToolHandoff(), capabilities, MemorySettings(),
    ))

    @Test fun `download cleanup completes before success and repeated Download cannot overlap it`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        engine.source = {
            flow {
                try { emit(ModelDownloadState.Completed) }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
        }
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        try {
            assertThat(vm.uiState.value.downloadState).isInstanceOf(ModelDownloadState.Started::class.java)
            assertThat(checks).isEqualTo(0)
            vm.onDownloadModel()
            runCurrent()
            assertThat(engine.downloads).isEqualTo(1)
        } finally { cleanup.complete(Unit) }
        runCurrent()
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
        assertThat(checks).isEqualTo(1)
    }

    @Test fun `check failures follow their mode and repeated check retries coalesce`() = runTest {
        checkFails = true
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        assertThat(vm.uiState.value.downloadCheckFailure).isNotNull()
        vm.onModeChange(TranscriptionMode.ADVANCED)
        runCurrent()
        assertThat(vm.uiState.value.downloadCheckFailure).isNull()
        vm.onModeChange(TranscriptionMode.BASIC)
        runCurrent()
        assertThat(vm.uiState.value.downloadCheckFailure).isNotNull()
        checkFails = false
        checkGate = CompletableDeferred()
        vm.onRetry()
        vm.onRetry()
        runCurrent()
        assertThat(checks).isEqualTo(2)
        assertThat(vm.uiState.value.isCheckingDownload).isTrue()
        checkGate!!.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.downloadCheckFailure).isNull()
        assertThat(vm.uiState.value.isCheckingDownload).isFalse()
        assertThat(engine.downloads).isEqualTo(1)
    }

    @Test fun `older readiness check cannot cancel a newer download attempt`() = runTest {
        engine.source = { flow { emit(ModelDownloadState.Failed(AiFailure.DownloadFailed("first"))) } }
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        engine.basicStatus = AiCapabilityStatus.AVAILABLE
        engine.statusGate = CompletableDeferred()
        vm.refreshAvailability()
        runCurrent()
        var newerClosed = false
        engine.source = {
            flow {
                try {
                    emit(ModelDownloadState.Started(null))
                    awaitCancellation()
                } finally { newerClosed = true }
            }
        }
        vm.onDownloadModel()
        runCurrent()
        val audio = Uri.parse("content://test/queued-audio")
        vm.onFileSelected(audio)
        engine.statusGate!!.complete(Unit)
        runCurrent()
        assertThat(newerClosed).isFalse()
        assertThat(vm.uiState.value.downloadState).isInstanceOf(ModelDownloadState.Started::class.java)
        assertThat(engine.downloads).isEqualTo(2)
        assertThat(vm.uiState.value.canStart).isFalse()
        assertThat(engine.files).isEmpty()
        // A fresh Check status does own this attempt and can reconcile it.
        vm.onRetry()
        runCurrent()
        assertThat(newerClosed).isTrue()
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
        assertThat(engine.files).containsExactly(audio)
    }

    @Test fun `completed download remains completed when capability refresh fails and Retry only checks`() = runTest {
        checkFails = true
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
        checkFails = false
        vm.onRetry()
        runCurrent()
        assertThat(engine.downloads).isEqualTo(1)
        assertThat(checks).isEqualTo(2)
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
    }

    @Test fun `Completed stops collection and closes download before checking readiness`() = runTest {
        var closed = false
        engine.source = {
            flow {
                engine.downloadOpen = true
                try {
                    engine.basicStatus = AiCapabilityStatus.AVAILABLE
                    emit(ModelDownloadState.Completed)
                    awaitCancellation()
                } finally {
                    engine.downloadOpen = false
                    closed = true
                }
            }
        }
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        assertThat(closed).isTrue()
        assertThat(engine.checksWhileDownloadOpen).isEqualTo(0)
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
    }

    @Test fun `Failed stops collection so Retry can start another attempt`() = runTest {
        var closed = false
        engine.source = {
            flow {
                try {
                    emit(ModelDownloadState.Failed(AiFailure.DownloadFailed("network")))
                    awaitCancellation()
                } finally { closed = true }
            }
        }
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        assertThat(closed).isTrue()
        engine.source = { flow { emit(ModelDownloadState.Completed) } }
        vm.onRetry()
        runCurrent()
        assertThat(engine.downloads).isEqualTo(2)
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
    }

    @Test fun `empty download flow ends with a retryable failure rather than eternal progress`() = runTest {
        engine.source = { emptyFlow() }
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        assertThat(vm.uiState.value.downloadState).isInstanceOf(ModelDownloadState.Failed::class.java)
        engine.source = { flow { emit(ModelDownloadState.Completed) } }
        vm.onRetry()
        runCurrent()
        assertThat(engine.downloads).isEqualTo(2)
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
    }

    @Test fun `status check recovers a lost completion callback and closes its collector`() = runTest {
        var closed = false
        engine.source = {
            flow {
                try {
                    emit(ModelDownloadState.Started(null))
                    awaitCancellation()
                } finally { closed = true }
            }
        }
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        engine.basicStatus = AiCapabilityStatus.AVAILABLE
        vm.refreshAvailability()
        runCurrent()
        assertThat(vm.uiState.value.downloadState).isEqualTo(ModelDownloadState.Completed)
        assertThat(closed).isTrue()
        assertThat(engine.downloads).isEqualTo(1)
    }

    @Test fun `download progress cannot dismiss a newer microphone permission error`() = runTest {
        val progress = CompletableDeferred<Unit>()
        engine.source = {
            flow {
                emit(ModelDownloadState.Started(null))
                progress.await()
                emit(ModelDownloadState.InProgress(1, 2))
                awaitCancellation()
            }
        }
        val vm = model()
        runCurrent()
        vm.onDownloadModel()
        runCurrent()
        vm.onMicrophoneDenied()
        val denied = vm.uiState.value.failure
        progress.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.failure).isSameInstanceAs(denied)
    }

    private class Speech : TranscriptionEngine {
        var basicStatus = AiCapabilityStatus.DOWNLOADABLE
        var downloads = 0
        val files = mutableListOf<Uri>()
        var downloadOpen = false
        var checksWhileDownloadOpen = 0
        var statusGate: CompletableDeferred<Unit>? = null
        var source: () -> Flow<ModelDownloadState> = {
            flow {
                basicStatus = AiCapabilityStatus.AVAILABLE
                emit(ModelDownloadState.Completed)
            }
        }
        override suspend fun status(mode: TranscriptionMode): AiCapabilityStatus {
            if (downloadOpen) checksWhileDownloadOpen++
            val result = if (mode == TranscriptionMode.BASIC) basicStatus else AiCapabilityStatus.UNSUPPORTED
            statusGate?.await()
            return result
        }
        override suspend fun provider(mode: TranscriptionMode) = AiProvider.GEMINI_NANO
        override suspend fun supportsFileInput(mode: TranscriptionMode) = true
        override fun downloadModel(mode: TranscriptionMode): Flow<ModelDownloadState> {
            downloads++
            return source()
        }
        override fun transcribeMicrophone(mode: TranscriptionMode): Flow<TranscriptChunk> = emptyFlow()
        override fun transcribeFile(uri: Uri, mode: TranscriptionMode): Flow<TranscriptChunk> = flow {
            files += uri
            emit(TranscriptChunk.Final("File transcript"))
            emit(TranscriptChunk.Completed)
        }
        override suspend fun stop() = Unit
        override fun release() = Unit
    }
}
