package com.localai.toolkit.ai.gemini

import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiException
import java.io.Closeable
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A fresh, independently owned client for one status, download or recognition operation. */
fun interface SpeechClientFactory {
    fun create(mode: TranscriptionMode): SpeechClient
}

interface SpeechClient : Closeable {
    suspend fun status(): AiCapabilityStatus
    fun download(): Flow<ModelDownloadState>
    fun recognize(source: AudioSource): Flow<TranscriptChunk>
    suspend fun stop()
}

class MlKitSpeechClientFactory @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SpeechClientFactory {
    override fun create(mode: TranscriptionMode): SpeechClient {
        val options = speechRecognizerOptions {
            locale = Locale.getDefault()
            preferredMode = when (mode) {
                TranscriptionMode.BASIC -> SpeechRecognizerOptions.Mode.MODE_BASIC
                TranscriptionMode.ADVANCED -> SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
            executor = ioDispatcher.asExecutor()
        }
        val client = SpeechRecognition.getClient(options)
        return object : SpeechClient {
            override suspend fun status() = client.checkStatus().toCapabilityStatus()
            override fun download() = client.download().map { it.toDownloadState() }
            override fun recognize(source: AudioSource): Flow<TranscriptChunk> = client.startRecognition(
                speechRecognizerRequest { audioSource = source },
            ).map { it.toTranscriptChunk() }
            override suspend fun stop() = client.stopRecognition()
            override fun close() = client.close()
        }
    }
}

internal fun SpeechRecognizerResponse.toTranscriptChunk(): TranscriptChunk = when (this) {
    is SpeechRecognizerResponse.PartialTextResponse -> TranscriptChunk.Partial(text)
    is SpeechRecognizerResponse.FinalTextResponse -> TranscriptChunk.Final(text)
    is SpeechRecognizerResponse.CompletedResponse -> TranscriptChunk.Completed
    is SpeechRecognizerResponse.ErrorResponse -> throw AiException(e.toAiFailure(), e)
}
