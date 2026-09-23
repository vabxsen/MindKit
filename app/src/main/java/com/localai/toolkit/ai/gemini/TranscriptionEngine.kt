package com.localai.toolkit.ai.gemini

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    private val clients: GenAiClientProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TranscriptionEngine {

    /** Whether the platform offers a recogniser that is guaranteed to stay on-device. */
    private val platformOnDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private var platformRecognizer: SpeechRecognizer? = null

    override suspend fun status(mode: TranscriptionMode): AiCapabilityStatus {
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
        mlKitStatus(mode) == AiCapabilityStatus.AVAILABLE

    override fun downloadModel(mode: TranscriptionMode): Flow<ModelDownloadState> =
        clients.speechRecognizer(mode.toMlKitMode()).download()
            .map { it.toDownloadState() }
            .catch { throwable -> emit(ModelDownloadState.Failed(throwable.toAiFailure())) }
            .flowOn(ioDispatcher)

    override fun transcribeMicrophone(mode: TranscriptionMode): Flow<TranscriptChunk> = flow {
        if (mlKitStatus(mode) == AiCapabilityStatus.AVAILABLE) {
            emitAll(mlKitRecognize(mode) { AudioSource.fromMic() })
        } else {
            emitAll(platformRecognize())
        }
    }

    override fun transcribeFile(uri: Uri, mode: TranscriptionMode): Flow<TranscriptChunk> =
        mlKitRecognize(mode) {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw AiException(AiFailure.InvalidInput(tooLarge = false))
            // ONE_SHOT: the whole file is available up front, unlike a live microphone.
            AudioSource.fromPfd(descriptor, AudioSource.Mode.ONE_SHOT)
        }

    override suspend fun stop() {
        withContext(ioDispatcher) { runCatching { clients.stopSpeechRecognition() } }
        // The platform recogniser must be driven from the main thread.
        withContext(Dispatchers.Main) { runCatching { platformRecognizer?.stopListening() } }
    }

    override fun release() {
        clients.closeSpeechRecognizers()
        platformRecognizer?.destroy()
        platformRecognizer = null
    }

    private suspend fun mlKitStatus(mode: TranscriptionMode): AiCapabilityStatus =
        withContext(ioDispatcher) {
            try {
                clients.speechRecognizer(mode.toMlKitMode()).checkStatus().toCapabilityStatus()
            } catch (e: Exception) {
                // No AICore, or no speech feature at all.
                if (e.toAiFailure() is AiFailure.Unsupported) {
                    AiCapabilityStatus.UNSUPPORTED
                } else {
                    AiCapabilityStatus.ERROR
                }
            }
        }

    private fun mlKitRecognize(
        mode: TranscriptionMode,
        audioSource: () -> AudioSource,
    ): Flow<TranscriptChunk> = flow {
        val recognizer = clients.speechRecognizer(mode.toMlKitMode())
        val request = speechRecognizerRequest { this.audioSource = audioSource() }

        recognizer.startRecognition(request).collect { response ->
            when (response) {
                is SpeechRecognizerResponse.PartialTextResponse ->
                    emit(TranscriptChunk.Partial(response.text))

                is SpeechRecognizerResponse.FinalTextResponse ->
                    emit(TranscriptChunk.Final(response.text))

                is SpeechRecognizerResponse.CompletedResponse ->
                    emit(TranscriptChunk.Completed)

                // Errors arrive in-band rather than as a throw, so they are re-thrown to
                // take the same mapped path as every other failure.
                is SpeechRecognizerResponse.ErrorResponse ->
                    throw AiException(response.e.toAiFailure(), response.e)
            }
        }
    }
        .flowOn(ioDispatcher)
        .catch { throwable ->
            if (throwable is AiException) throw throwable
            throw AiException(throwable.toAiFailure(), throwable)
        }

    /**
     * The platform on-device recogniser, as a Flow.
     *
     * [SpeechRecognizer] must be created and driven on the main thread, which is why this
     * flow is pinned to [Dispatchers.Main] rather than the IO dispatcher used elsewhere.
     */
    private fun platformRecognize(): Flow<TranscriptChunk> = callbackFlow {
        if (!platformOnDeviceAvailable) {
            throw AiException(
                AiFailure.Unsupported("No on-device platform speech recogniser"),
            )
        }

        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        platformRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { trySend(TranscriptChunk.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { trySend(TranscriptChunk.Final(it)) }
                trySend(TranscriptChunk.Completed)
                channel.close()
            }

            override fun onError(error: Int) {
                channel.close(AiException(platformErrorToFailure(error)))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        recognizer.startListening(intent)

        awaitClose {
            recognizer.stopListening()
            recognizer.destroy()
            platformRecognizer = null
        }
    }.flowOn(Dispatchers.Main)

    private fun TranscriptionMode.toMlKitMode(): Int = when (this) {
        TranscriptionMode.BASIC -> SpeechRecognizerOptions.Mode.MODE_BASIC
        TranscriptionMode.ADVANCED -> SpeechRecognizerOptions.Mode.MODE_ADVANCED
    }
}

/** Platform recogniser error codes, mapped into the app's failure vocabulary. */
private fun platformErrorToFailure(error: Int): AiFailure = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        AiFailure.Unsupported("RECORD_AUDIO not granted")

    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> AiFailure.Busy()

    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> AiFailure.InvalidInput(tooLarge = false, technicalDetail = "No speech detected")

    SpeechRecognizer.ERROR_CLIENT -> AiFailure.Cancelled("Recogniser client error")

    else -> AiFailure.Unknown("SpeechRecognizer error=$error")
}
