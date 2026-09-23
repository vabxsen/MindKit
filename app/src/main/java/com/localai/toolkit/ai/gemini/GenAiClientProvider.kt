package com.localai.toolkit.ai.gemini

import android.content.Context
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiTask
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.Locale
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor

/**
 * Owns every ML Kit GenAI client in the app.
 *
 * Two things make a shared owner worth having:
 *
 *  - These clients hold an AICore session. Creating one per call would be slow, and
 *    leaving several open would keep system inference resources tied up for a screen the
 *    user has already left. So exactly one client per feature is cached, and it is closed
 *    when [close] is called or when the request options change.
 *  - The capability screen and the inference path both need a client to ask the system
 *    anything. Sharing one cache means checking availability does not create a second
 *    session alongside the one doing the work.
 *
 * The feature clients are keyed by their options because ML Kit bakes options into the
 * client: a rewriter built for PROFESSIONAL cannot produce FRIENDLY output. When the key
 * changes the previous client is closed first.
 */
@Singleton
class GenAiClientProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val lock = Any()

    private var generativeModel: GenerativeModel? = null

    private var summarizer: Summarizer? = null
    private var summarizerKey: SummarizerKey? = null

    private var rewriter: Rewriter? = null
    private var rewriterKey: RewriterKey? = null

    private var proofreader: Proofreader? = null
    private var proofreaderKey: ProofreaderKey? = null

    private var imageDescriber: ImageDescriber? = null

    // Keyed by SpeechRecognizerOptions.Mode. Basic and advanced are genuinely different
    // clients, and the capability screen wants the status of both, so both are cached.
    private val speechRecognizers = mutableMapOf<Int, SpeechRecognizer>()

    /**
     * The Prompt API client.
     *
     * [Generation.getClient] takes no Context: the library resolves the application
     * context itself through its own content provider.
     */
    fun promptModel(): GenerativeModel = synchronized(lock) {
        generativeModel ?: Generation.getClient().also { generativeModel = it }
    }

    fun summarizer(inputType: Int, outputType: Int, language: Int): Summarizer =
        synchronized(lock) {
            val key = SummarizerKey(inputType, outputType, language)
            summarizer?.takeIf { summarizerKey == key }?.let { return it }

            summarizer?.close()
            val options = SummarizerOptions.builder(context)
                .setInputType(inputType)
                .setOutputType(outputType)
                .setLanguage(language)
                // Long inputs are truncated by the API rather than rejected outright,
                // which is a better outcome than failing on a pasted article.
                .setLongInputAutoTruncationEnabled(true)
                .build()
            Summarization.getClient(options).also {
                summarizer = it
                summarizerKey = key
            }
        }

    fun rewriter(outputType: Int, language: Int): Rewriter = synchronized(lock) {
        val key = RewriterKey(outputType, language)
        rewriter?.takeIf { rewriterKey == key }?.let { return it }

        rewriter?.close()
        val options = RewriterOptions.builder(context)
            .setOutputType(outputType)
            .setLanguage(language)
            .build()
        Rewriting.getClient(options).also {
            rewriter = it
            rewriterKey = key
        }
    }

    fun proofreader(inputType: Int, language: Int): Proofreader = synchronized(lock) {
        val key = ProofreaderKey(inputType, language)
        proofreader?.takeIf { proofreaderKey == key }?.let { return it }

        proofreader?.close()
        val options = ProofreaderOptions.builder(context)
            .setInputType(inputType)
            .setLanguage(language)
            .build()
        Proofreading.getClient(options).also {
            proofreader = it
            proofreaderKey = key
        }
    }

    fun imageDescriber(): ImageDescriber = synchronized(lock) {
        imageDescriber ?: ImageDescription.getClient(
            ImageDescriberOptions.builder(context).build(),
        ).also { imageDescriber = it }
    }

    /**
     * A speech recogniser for one [SpeechRecognizerOptions.Mode].
     *
     * The executor is the app's IO dispatcher rather than a thread the library creates,
     * so recognition shares the same pool as the rest of the app's background work.
     */
    fun speechRecognizer(mode: Int): SpeechRecognizer = synchronized(lock) {
        speechRecognizers.getOrPut(mode) {
            val options = speechRecognizerOptions {
                locale = Locale.getDefault()
                preferredMode = mode
                executor = ioDispatcher.asExecutor()
            }
            SpeechRecognition.getClient(options)
        }
    }

    /** Asks any active recogniser to finish its current utterance. */
    suspend fun stopSpeechRecognition() {
        val active = synchronized(lock) { speechRecognizers.values.toList() }
        active.forEach { it.stopRecognition() }
    }

    fun closeSpeechRecognizers() = synchronized(lock) {
        speechRecognizers.values.forEach { it.close() }
        speechRecognizers.clear()
    }

    /**
     * Closes the client backing [task], or every client when null.
     *
     * Called when a tool screen is destroyed so that a backgrounded app is not still
     * holding an inference session open.
     */
    fun close(task: AiTask?) = synchronized(lock) {
        when (task) {
            null -> {
                closePrompt()
                closeSummarizer()
                closeRewriter()
                closeProofreader()
                closeImageDescriber()
                speechRecognizers.values.forEach { it.close() }
                speechRecognizers.clear()
            }

            AiTask.ASK, AiTask.IMAGE_QUESTION -> closePrompt()
            AiTask.SUMMARIZE -> closeSummarizer()
            AiTask.REWRITE -> closeRewriter()
            AiTask.PROOFREAD -> closeProofreader()
            AiTask.IMAGE_DESCRIPTION -> closeImageDescriber()

            // Nothing GenAI-backed to release for these.
            AiTask.BASIC_TRANSCRIPTION, AiTask.ADVANCED_TRANSCRIPTION -> closeSpeechRecognizers()

            // Not GenAI-backed, so there is nothing here to release.
            AiTask.TEXT_RECOGNITION, AiTask.TRANSLATION -> Unit
        }
    }

    private fun closePrompt() {
        generativeModel?.close()
        generativeModel = null
    }

    private fun closeSummarizer() {
        summarizer?.close()
        summarizer = null
        summarizerKey = null
    }

    private fun closeRewriter() {
        rewriter?.close()
        rewriter = null
        rewriterKey = null
    }

    private fun closeProofreader() {
        proofreader?.close()
        proofreader = null
        proofreaderKey = null
    }

    private fun closeImageDescriber() {
        imageDescriber?.close()
        imageDescriber = null
    }

    private data class SummarizerKey(val inputType: Int, val outputType: Int, val language: Int)
    private data class RewriterKey(val outputType: Int, val language: Int)
    private data class ProofreaderKey(val inputType: Int, val language: Int)
}
