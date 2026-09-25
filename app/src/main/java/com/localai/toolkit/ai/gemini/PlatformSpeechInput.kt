package com.localai.toolkit.ai.gemini

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Narrow test seam for Android's main-thread-only recognition service. */
internal interface PlatformRecognizer {
    fun setListener(listener: RecognitionListener)
    fun start(intent: Intent)
    fun stop()
    fun cancel()
    fun destroy()
}

/** Owns the platform microphone independently of ML Kit model/status clients. */
@Singleton
class PlatformSpeechInput internal constructor(
    private val available: () -> Boolean,
    private val create: () -> PlatformRecognizer,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        available = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        },
        create = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw AiException(AiFailure.Unsupported("On-device speech requires Android 12 or newer"))
            }
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            object : PlatformRecognizer {
                override fun setListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
                override fun start(intent: Intent) = recognizer.startListening(intent)
                override fun stop() = recognizer.stopListening()
                override fun cancel() = recognizer.cancel()
                override fun destroy() = recognizer.destroy()
            }
        },
    )

    private class Session(val recognizer: PlatformRecognizer, val closeStream: () -> Unit) {
        var closed = false
        fun close() {
            if (closed) return
            closed = true
            closeStream()
            // A service failure in cancel must not prevent destroy.
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private var active: Session? = null

    fun recognize(): Flow<TranscriptChunk> = callbackFlow {
        if (!available()) throw AiException(AiFailure.Unsupported("No on-device platform speech recogniser"))
        if (active != null) throw AiException(AiFailure.Busy())
        val session = Session(create()) { channel.close() }
        active = session
        try {
            session.recognizer.setListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.text()?.let { trySend(TranscriptChunk.Partial(it)) }
                }
                override fun onResults(results: Bundle?) {
                    results.text()?.let { trySend(TranscriptChunk.Final(it)) }
                    trySend(TranscriptChunk.Completed)
                    channel.close()
                }
                override fun onError(error: Int) {
                    channel.close(AiException(platformErrorToFailure(error)))
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }
            session.recognizer.start(intent)
            awaitClose { }
        } finally {
            // This also runs if listener registration or startListening throws before awaitClose.
            if (active === session) active = null
            session.close()
        }
    }.flowOn(Dispatchers.Main).catch { error ->
        if (error is CancellationException || error is AiException) throw error
        throw AiException(AiFailure.Unknown(error.message), error)
    }

    suspend fun stop() = withContext(Dispatchers.Main) { active?.recognizer?.stop(); Unit }

    /** Called by the screen's main-thread lifecycle; stale collectors cannot release a new session. */
    @MainThread
    fun release() {
        val session = active
        active = null
        session?.close()
    }

    private fun Bundle?.text(): String? = this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.takeIf { it.isNotBlank() }
}

private fun platformErrorToFailure(error: Int): AiFailure = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> AiFailure.Unsupported("RECORD_AUDIO not granted")
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> AiFailure.Busy()
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
        AiFailure.InvalidInput(tooLarge = false, technicalDetail = "No speech detected")
    SpeechRecognizer.ERROR_CLIENT -> AiFailure.Cancelled("Recogniser client error")
    else -> AiFailure.Unknown("SpeechRecognizer error=$error")
}
