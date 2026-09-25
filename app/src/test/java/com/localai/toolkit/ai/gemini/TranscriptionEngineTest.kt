package com.localai.toolkit.ai.gemini

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.localai.toolkit.ai.audio.AudioDecoder
import com.localai.toolkit.ai.audio.PcmAudioInput
import com.localai.toolkit.ai.audio.PcmPipe
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionEngineTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    private val factory = Factory()
    private val platformClients = mutableListOf<PlatformClient>()
    private val pipes = mutableListOf<Pipe>()
    private val decoderStarted = CompletableDeferred<Unit>()
    private var decoderClosed = false
    private val engine by lazy {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        DefaultTranscriptionEngine(
            ApplicationProvider.getApplicationContext(), factory, main.dispatcher,
            PcmAudioInput(object : AudioDecoder {
                override suspend fun decode(uri: Uri, emit: suspend (ByteArray) -> Unit) {
                    try {
                        decoderStarted.complete(Unit)
                        emit(ByteArray(640))
                        awaitCancellation()
                    } finally { decoderClosed = true }
                }
            }, main.dispatcher, {
                Pipe(ParcelFileDescriptor.open(temporary.newFile(), ParcelFileDescriptor.MODE_READ_ONLY))
                    .also { pipes += it }
            }),
            PlatformSpeechInput({ true }, { PlatformClient().also { platformClients += it } }),
        )
    }

    @Test fun `downloads are cold and each collection owns a distinct client`() = runTest {
        factory.configure = { it.downloadFinished.complete(Unit) }
        val download = engine.downloadModel(TranscriptionMode.ADVANCED)
        assertThat(factory.clients).isEmpty()
        assertThat(download.toList().last()).isEqualTo(ModelDownloadState.Completed)
        assertThat(download.toList().last()).isEqualTo(ModelDownloadState.Completed)
        assertThat(factory.clients).hasSize(2)
        assertThat(factory.modes).containsExactly(TranscriptionMode.ADVANCED, TranscriptionMode.ADVANCED)
        assertThat(factory.clients.map { it.closeCalls }).containsExactly(1, 1)
    }

    @Test fun `release recording does not close an overlapping download or status check`() = runTest {
        val download = async { engine.downloadModel(TranscriptionMode.ADVANCED).toList() }
        runCurrent()
        val downloader = factory.clients.single()
        val recording = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        val recognizer = factory.clients.single { it.recognizeCalls > 0 }
        val statusGate = CompletableDeferred<Unit>()
        factory.configure = { it.statusGate = statusGate }
        val check = async { engine.status(TranscriptionMode.ADVANCED) }
        runCurrent()
        val checker = factory.clients.last()
        engine.release()
        runCurrent()
        assertThat(recording.isCancelled).isTrue()
        assertThat(recognizer.closeCalls).isEqualTo(1)
        assertThat(downloader.closeCalls).isEqualTo(0)
        assertThat(checker.closeCalls).isEqualTo(0)
        statusGate.complete(Unit)
        downloader.downloadFinished.complete(Unit)
        runCurrent()
        assertThat(check.await()).isEqualTo(AiCapabilityStatus.AVAILABLE)
        assertThat(download.await().last()).isEqualTo(ModelDownloadState.Completed)
        assertThat(downloader.closeCalls).isEqualTo(1)
        assertThat(checker.closeCalls).isEqualTo(1)
    }

    @Test fun `Stop only addresses active recognition not downloading or status clients`() = runTest {
        val download = async { engine.downloadModel(TranscriptionMode.ADVANCED).toList() }
        runCurrent()
        val recording = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        val recognizer = factory.clients.single { it.recognizeCalls > 0 }
        engine.stop()
        assertThat(recognizer.stopCalls).isEqualTo(1)
        assertThat(factory.clients.filter { it !== recognizer }.map { it.stopCalls }).containsExactly(0, 0)
        recording.cancel()
        download.cancel()
        runCurrent()
        assertThat(factory.clients.map { it.closeCalls }).containsExactly(1, 1, 1)
    }

    @Test fun `cancel download closes only its client and recording continues`() = runTest {
        val download = async { engine.downloadModel(TranscriptionMode.ADVANCED).toList() }
        runCurrent()
        val downloader = factory.clients.single()
        val recording = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        val recognizer = factory.clients.single { it.recognizeCalls > 0 }
        download.cancel()
        runCurrent()
        assertThat(downloader.closeCalls).isEqualTo(1)
        assertThat(recognizer.closeCalls).isEqualTo(0)
        assertThat(recording.isActive).isTrue()
        recording.cancel()
        runCurrent()
    }

    @Test fun `cancelling a status check propagates cancellation and closes its client`() = runTest {
        factory.configure = { it.statusGate = CompletableDeferred() }
        val check = async { engine.status(TranscriptionMode.BASIC) }
        runCurrent()
        check.cancel()
        runCurrent()
        assertThat(check.isCancelled).isTrue()
        assertThat(factory.clients.single().closeCalls).isEqualTo(1)
        assertThat(platformClients).isEmpty()
    }

    @Test fun `release during recording setup cancels setup and allows a subsequent recording`() = runTest {
        factory.configure = { it.statusGate = CompletableDeferred() }
        val first = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        engine.release()
        runCurrent()
        assertThat(first.isCancelled).isTrue()
        assertThat(factory.clients.single().closeCalls).isEqualTo(1)
        assertThat(factory.clients.single().recognizeCalls).isEqualTo(0)
        factory.configure = { }
        val next = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        assertThat(factory.clients.last().recognizeCalls).isEqualTo(1)
        next.cancel()
        runCurrent()
    }

    @Test fun `Stop during startup cancels setup instead of starting a microphone later`() = runTest {
        factory.configure = { it.statusGate = CompletableDeferred() }
        val recording = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        engine.stop()
        runCurrent()
        assertThat(recording.isCancelled).isTrue()
        assertThat(factory.clients.single().closeCalls).isEqualTo(1)
        assertThat(factory.clients.single().recognizeCalls).isEqualTo(0)
    }

    @Test fun `concurrent recognition is rejected without closing the first client`() = runTest {
        val first = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        val clientCount = factory.clients.size
        val second = async { runCatching { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }.exceptionOrNull() }
        runCurrent()
        assertThat((second.await() as AiException).failure).isInstanceOf(AiFailure.Busy::class.java)
        assertThat(factory.clients).hasSize(clientCount)
        assertThat(factory.clients.last().closeCalls).isEqualTo(0)
        first.cancel()
        runCurrent()
    }

    @Test fun `Completed closes an upstream that never ends and releases recording ownership`() = runTest {
        factory.configure = { it.recognitionCompletes = true }
        val result = engine.transcribeMicrophone(TranscriptionMode.BASIC).toList()
        assertThat(result).containsExactly(TranscriptChunk.Partial("words"), TranscriptChunk.Completed).inOrder()
        assertThat(factory.clients.map { it.closeCalls }).containsExactly(1, 1)
        assertThat(factory.clients.last().recognitionUnwound).isTrue()
        engine.transcribeMicrophone(TranscriptionMode.BASIC).toList()
        assertThat(factory.clients).hasSize(4)
    }

    @Test fun `recognition setup error stays primary when client close also fails`() = runTest {
        factory.configure = { client ->
            if (factory.clients.size % 2 == 1) {
                client.recognizeFailure = AiException(AiFailure.Busy())
                client.closeFails = true
            }
        }
        val failure = runCatching { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }.exceptionOrNull()
        assertThat((failure as AiException).failure).isInstanceOf(AiFailure.Busy::class.java)
        assertThat(failure.suppressed).hasLength(1)
        assertThat(factory.clients.last().closeCalls).isEqualTo(1)
        factory.configure = { it.recognitionCompletes = true }
        assertThat(engine.transcribeMicrophone(TranscriptionMode.BASIC).toList().last()).isEqualTo(TranscriptChunk.Completed)
    }

    @Test fun `Stop failure is reported without touching independent clients`() = runTest {
        factory.configure = { it.stopFails = true }
        val recording = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        assertThat(runCatching { engine.stop() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        engine.release()
        runCurrent()
        assertThat(recording.isCancelled).isTrue()
        assertThat(factory.clients.last().closeCalls).isEqualTo(1)
    }

    @Test fun `Advanced unavailable never silently starts the Android basic recognizer`() = runTest {
        factory.configure = { it.availability = AiCapabilityStatus.UNSUPPORTED }
        val failure = runCatching { engine.transcribeMicrophone(TranscriptionMode.ADVANCED).toList() }.exceptionOrNull()
        assertThat((failure as AiException).failure).isInstanceOf(AiFailure.Unsupported::class.java)
        assertThat(platformClients).isEmpty()
        assertThat(factory.clients.single().closeCalls).isEqualTo(1)
    }

    @Test fun `Advanced status failure reports error without platform fallback`() = runTest {
        factory.configure = { it.statusFails = true }
        val failure = runCatching { engine.transcribeMicrophone(TranscriptionMode.ADVANCED).toList() }.exceptionOrNull()
        assertThat((failure as AiException).failure).isInstanceOf(AiFailure.Unknown::class.java)
        assertThat(platformClients).isEmpty()
        assertThat(factory.clients.single().closeCalls).isEqualTo(1)
    }

    @Test fun `Basic unsupported uses platform but Stop leaves its separate download alone`() = runTest {
        factory.configure = { it.availability = AiCapabilityStatus.UNSUPPORTED }
        val download = async { engine.downloadModel(TranscriptionMode.ADVANCED).toList() }
        runCurrent()
        val downloader = factory.clients.single()
        val recording = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        assertThat(platformClients).hasSize(1)
        engine.stop()
        assertThat(platformClients.single().stopCalls).isEqualTo(1)
        assertThat(downloader.stopCalls).isEqualTo(0)
        engine.release()
        runCurrent()
        assertThat(recording.isCancelled).isTrue()
        assertThat(platformClients.single().destroyCalls).isEqualTo(1)
        assertThat(downloader.closeCalls).isEqualTo(0)
        download.cancel()
        runCurrent()
    }

    @Test fun `download-required mode does not bypass installation with platform recognition`() = runTest {
        factory.configure = { it.availability = AiCapabilityStatus.DOWNLOADABLE }
        val failure = runCatching { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }.exceptionOrNull()
        assertThat((failure as AiException).failure).isInstanceOf(AiFailure.ModelNotDownloaded::class.java)
        assertThat(platformClients).isEmpty()
    }

    @Test fun `file cancellation closes decoder pipe and only its recognition client`() = runTest {
        val download = async { engine.downloadModel(TranscriptionMode.ADVANCED).toList() }
        runCurrent()
        val downloader = factory.clients.single()
        val recording = async { engine.transcribeFile(Uri.parse("content://test/audio"), TranscriptionMode.BASIC).toList() }
        runCurrent()
        val client = factory.clients.last()
        assertThat(decoderStarted.isCompleted).isTrue()
        assertThat(client.source?.pfd).isSameInstanceAs(pipes.single().reader)
        engine.release()
        runCurrent()
        assertThat(recording.isCancelled).isTrue()
        assertThat(client.closeCalls).isEqualTo(1)
        assertThat(pipes.single().closed).isTrue()
        assertThat(decoderClosed).isTrue()
        assertThat(downloader.closeCalls).isEqualTo(0)
        download.cancel()
        runCurrent()
    }

    @Test fun `download factory failure is a cold observable failure not a thrown button exception`() = runTest {
        factory.createFails = true
        val download = engine.downloadModel(TranscriptionMode.BASIC)
        assertThat(factory.clients).isEmpty()
        assertThat(download.toList().single()).isInstanceOf(ModelDownloadState.Failed::class.java)
    }

    @Test fun `SDK text and terminal responses preserve their app meanings`() {
        assertThat(SpeechRecognizerResponse.PartialTextResponse("part").toTranscriptChunk()).isEqualTo(TranscriptChunk.Partial("part"))
        assertThat(SpeechRecognizerResponse.FinalTextResponse("done").toTranscriptChunk()).isEqualTo(TranscriptChunk.Final("done"))
        assertThat(SpeechRecognizerResponse.CompletedResponse.toTranscriptChunk()).isEqualTo(TranscriptChunk.Completed)
    }

    @Test fun `SDK in-band error becomes the same specific app failure`() {
        val vendor = GenAiException(null, GenAiException.ErrorCode.BUSY)
        val error = runCatching { SpeechRecognizerResponse.ErrorResponse(vendor).toTranscriptChunk() }.exceptionOrNull()
        assertThat((error as AiException).failure).isInstanceOf(AiFailure.Busy::class.java)
        assertThat(error.cause).isSameInstanceAs(vendor)
    }

    @Test fun `close failure during cancellation cannot poison subsequent recognition`() = runTest {
        factory.configure = { if (factory.clients.size % 2 == 1) it.closeFails = true }
        val recording = async { engine.transcribeMicrophone(TranscriptionMode.BASIC).toList() }
        runCurrent()
        engine.release()
        runCurrent()
        assertThat(recording.isCancelled).isTrue()
        assertThat(factory.clients.last().closeCalls).isEqualTo(1)
        factory.configure = { it.recognitionCompletes = true }
        assertThat(engine.transcribeMicrophone(TranscriptionMode.BASIC).toList().last()).isEqualTo(TranscriptChunk.Completed)
    }

    private class Factory : SpeechClientFactory {
        val clients = mutableListOf<Client>()
        val modes = mutableListOf<TranscriptionMode>()
        var configure: (Client) -> Unit = { }
        var createFails = false
        override fun create(mode: TranscriptionMode): SpeechClient {
            if (createFails) error("client factory failed")
            modes += mode
            return Client().also { configure(it); clients += it }
        }
    }

    private class Client : SpeechClient {
        var availability = AiCapabilityStatus.AVAILABLE
        var statusGate = CompletableDeferred(Unit)
        val downloadFinished = CompletableDeferred<Unit>()
        var statusFails = false
        var closeFails = false
        var stopFails = false
        var recognizeFailure: Exception? = null
        var recognitionCompletes = false
        var recognitionUnwound = false
        var recognizeCalls = 0
        var stopCalls = 0
        var closeCalls = 0
        var source: AudioSource? = null
        override suspend fun status(): AiCapabilityStatus {
            statusGate.await()
            if (statusFails) error("status failed")
            return availability
        }
        override fun download(): Flow<ModelDownloadState> = flow {
            emit(ModelDownloadState.Started(null))
            downloadFinished.await()
            emit(ModelDownloadState.Completed)
            awaitCancellation()
        }
        override fun recognize(source: AudioSource): Flow<TranscriptChunk> {
            recognizeCalls++
            this.source = source
            recognizeFailure?.let { throw it }
            return flow {
                try {
                    emit(TranscriptChunk.Partial("words"))
                    if (recognitionCompletes) emit(TranscriptChunk.Completed)
                    awaitCancellation()
                } finally { recognitionUnwound = true }
            }
        }
        override suspend fun stop() { stopCalls++; if (stopFails) error("stop failed") }
        override fun close() { closeCalls++; if (closeFails) error("close failed") }
    }

    private class PlatformClient : PlatformRecognizer {
        var stopCalls = 0
        var destroyCalls = 0
        override fun setListener(listener: RecognitionListener) = Unit
        override fun start(intent: Intent) = Unit
        override fun stop() { stopCalls++ }
        override fun cancel() = Unit
        override fun destroy() { destroyCalls++ }
    }

    private class Pipe(override val reader: ParcelFileDescriptor) : PcmPipe {
        var closed = false
        override suspend fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
        override fun finishWriting() = Unit
        override fun close() { closed = true; reader.close() }
    }
}
