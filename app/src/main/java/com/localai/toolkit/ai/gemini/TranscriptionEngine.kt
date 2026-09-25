package com.localai.toolkit.ai.gemini

import android.content.Context
import android.net.Uri
import android.os.Build
import android.speech.SpeechRecognizer
import com.google.mlkit.genai.common.audio.AudioSource
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.audio.PcmAudioInput
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext

/**
 * Which recognition quality to ask for.
 *
 * ML Kit treats this as a *preferred* mode, so asking for advanced does not guarantee it.
 * The capability screen reports what each mode actually resolved to.
 */
enum class TranscriptionMode { BASIC, ADVANCED }

/** One step of a transcription. */
sealed interface TranscriptChunk {
    /** Interim text that may still change as the speaker continues. */
    data class Partial(val text: String) : TranscriptChunk

    /** A settled segment. Appending these builds the final transcript. */
    data class Final(val text: String) : TranscriptChunk

    /** Recognition finished normally. */
    data object Completed : TranscriptChunk
}

/**
 * On-device speech recognition.
 *
 * Kept out of [com.localai.toolkit.ai.engine.AiEngine] because its shape is genuinely
 * different - a long-lived stream with a stop signal, rather than a request and a
 * response - and folding it in would have made that interface worse for every other tool.
 */
interface TranscriptionEngine {

    /** Runtime availability of [mode] on this device. */
    suspend fun status(mode: TranscriptionMode): AiCapabilityStatus

    /**
     * Which engine would actually serve [mode].
     *
     * Surfaced so the UI can say "Gemini Nano" or "Android on-device" truthfully rather
     * than assuming.
     */
    suspend fun provider(mode: TranscriptionMode): AiProvider

    /** True when [mode] can transcribe a file rather than only a live microphone. */
    suspend fun supportsFileInput(mode: TranscriptionMode): Boolean

    fun downloadModel(mode: TranscriptionMode): Flow<ModelDownloadState>

    /**
     * Transcribes live microphone audio.
     *
     * The caller must already hold RECORD_AUDIO. The flow runs until [stop] is called or
     * the collector is cancelled.
     */
    fun transcribeMicrophone(mode: TranscriptionMode): Flow<TranscriptChunk>

    /**
     * Transcribes an audio file the user picked.
     *
     * Read through the content URI the picker granted; the file is never copied into the
     * app's storage.
     */
    fun transcribeFile(uri: Uri, mode: TranscriptionMode): Flow<TranscriptChunk>

    /** Asks the recogniser to finish the current utterance. */
    suspend fun stop()

    fun release()
}

/**
 * Speech recognition backed by ML Kit GenAI, falling back to the platform recogniser.
 *
 * The fallback is deliberately narrow. [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 * is the only platform path used, because it is the only one guaranteed not to send audio
 * to a server. The general recogniser is *not* used even with EXTRA_PREFER_OFFLINE, since
 * that flag is a preference rather than a guarantee - and an app that promises local
 * processing must not quietly stream a user's voice to the network.
 *
 * Consequence: on a device with neither ML Kit speech nor an on-device platform
 * recogniser, transcription reports unsupported. That is the honest answer.
 */
@Singleton
class DefaultTranscriptionEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clients: SpeechClientFactory,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val pcmAudioInput: PcmAudioInput,
    private val platformInput: PlatformSpeechInput,
) : TranscriptionEngine {

    private class RecognitionSession(val job: Job) {
        @Volatile var stopAction: (suspend () -> Unit)? = null
    }

    private val activeRecognition = AtomicReference<RecognitionSession?>()

    /** Whether the platform offers a recogniser that is guaranteed to stay on-device. */
    private val platformOnDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override suspend fun status(mode: TranscriptionMode): AiCapabilityStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return AiCapabilityStatus.UNSUPPORTED
        val mlKit = mlKitStatus(mode)
        if (mlKit != AiCapabilityStatus.UNSUPPORTED && mlKit != AiCapabilityStatus.ERROR) {
            return mlKit
        }
        // Only the basic mode has a meaningful platform equivalent; the platform
        // recogniser is not a substitute for the advanced GenAI model.
        if (mode == TranscriptionMode.BASIC && platformOnDeviceAvailable) {
            return AiCapabilityStatus.AVAILABLE
        }
        return mlKit
    }

    override suspend fun provider(mode: TranscriptionMode): AiProvider {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return AiProvider.NONE
        val mlKit = mlKitStatus(mode)
        return when {
            mlKit == AiCapabilityStatus.AVAILABLE ||
                mlKit == AiCapabilityStatus.DOWNLOADABLE ||
                mlKit == AiCapabilityStatus.DOWNLOADING -> AiProvider.GEMINI_NANO

            mode == TranscriptionMode.BASIC && platformOnDeviceAvailable ->
                AiProvider.ANDROID_PLATFORM

            else -> AiProvider.NONE
        }
    }

    override suspend fun supportsFileInput(mode: TranscriptionMode): Boolean =
        // Only ML Kit can be handed a file descriptor; the platform recogniser listens to
        // the microphone and nothing else.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            mlKitStatus(mode) == AiCapabilityStatus.AVAILABLE

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun downloadModel(mode: TranscriptionMode): Flow<ModelDownloadState> = flow {
        requireSpeechSdk()
        // Creation is cold, and only this download owns/closes this client.
        clients.create(mode).use { client ->
            emitAll(client.download().transformWhile { state ->
                emit(state)
                state != ModelDownloadState.Completed && state !is ModelDownloadState.Failed
            })
        }
    }.flowOn(ioDispatcher).catch { error ->
        if (error is CancellationException) throw error
        emit(ModelDownloadState.Failed((error as? AiException)?.failure ?: error.toAiFailure()))
    }

    override fun transcribeMicrophone(mode: TranscriptionMode): Flow<TranscriptChunk> = recognitionFlow { session ->
        // Keep this guard local so lint can verify the SDK's API-31 microphone call.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw AiException(AiFailure.Unsupported("On-device speech requires Android 12 or newer"))
        }
        val status = mlKitStatus(mode)
        if (status == AiCapabilityStatus.AVAILABLE) {
            emitAll(mlKitRecognize(session, mode) { AudioSource.fromMic() })
        } else if (mode == TranscriptionMode.BASIC &&
            (status == AiCapabilityStatus.UNSUPPORTED || status == AiCapabilityStatus.ERROR) &&
            platformOnDeviceAvailable
        ) {
            session.stopAction = {
                withContext(Dispatchers.Main) {
                    if (activeRecognition.get() === session) platformInput.stop()
                }
            }
            emitAll(platformInput.recognize())
        } else {
            throw AiException(when (status) {
                AiCapabilityStatus.UNSUPPORTED -> AiFailure.Unsupported("Requested speech mode is unavailable")
                AiCapabilityStatus.DOWNLOADABLE, AiCapabilityStatus.DOWNLOADING -> AiFailure.ModelNotDownloaded()
                else -> AiFailure.Unknown("Unable to establish speech availability")
            })
        }
    }

    override fun transcribeFile(uri: Uri, mode: TranscriptionMode): Flow<TranscriptChunk> =
        recognitionFlow { session ->
            emitAll(pcmAudioInput.recognize(uri) { descriptor ->
                mlKitRecognize(session, mode) {
                    // Encoded/container bytes are decoded locally into a paced PCM stream.
                    AudioSource.fromPfd(descriptor, AudioSource.Mode.STREAMING)
                }
            })
        }

    override suspend fun stop() {
        withContext(ioDispatcher) {
            val session = activeRecognition.get() ?: return@withContext
            val stop = session.stopAction
            // Stop during setup means no utterance exists yet; cancel that setup.
            if (stop == null) session.job.cancel() else stop()
        }
    }

    override fun release() {
        // The owning collection closes its client in finally. Do not touch independent
        // downloads/status checks or close a client concurrently with its setup.
        activeRecognition.get()?.job?.cancel()
        platformInput.release()
    }

    private suspend fun mlKitStatus(mode: TranscriptionMode): AiCapabilityStatus =
        withContext(ioDispatcher) {
            try {
                clients.create(mode).use { it.status() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // No AICore, or no speech feature at all.
                if (((e as? AiException)?.failure ?: e.toAiFailure()) is AiFailure.Unsupported) {
                    AiCapabilityStatus.UNSUPPORTED
                } else {
                    AiCapabilityStatus.ERROR
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun mlKitRecognize(
        session: RecognitionSession,
        mode: TranscriptionMode,
        audioSource: () -> AudioSource,
    ): Flow<TranscriptChunk> = flow {
        clients.create(mode).use { recognizer ->
            session.stopAction = { recognizer.stop() }
            emitAll(recognizer.recognize(audioSource()).transformWhile { chunk ->
                emit(chunk)
                chunk != TranscriptChunk.Completed
            })
        }
    }

    private fun recognitionFlow(
        block: suspend FlowCollector<TranscriptChunk>.(RecognitionSession) -> Unit,
    ): Flow<TranscriptChunk> = flow {
        requireSpeechSdk()
        coroutineScope {
            val session = RecognitionSession(coroutineContext.job)
            if (!activeRecognition.compareAndSet(null, session)) throw AiException(AiFailure.Busy())
            try {
                block(session)
            } finally {
                activeRecognition.compareAndSet(session, null)
            }
        }
    }.flowOn(ioDispatcher).catch { error ->
        if (error is CancellationException || error is AiException) throw error
        throw AiException(error.toAiFailure(), error)
    }

    private fun requireSpeechSdk() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw AiException(AiFailure.Unsupported("On-device speech requires Android 12 or newer"))
        }
    }
}
