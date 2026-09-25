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
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.localai.toolkit.domain.model.AiTask
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns reusable, option-keyed text/image SDK clients. Every status check, download
 * and inference must hold a lease for its entire operation, including cleanup.
 * Changing options or releasing a screen retires the cached client without closing
 * it under another active user. Speech uses operation-scoped [SpeechClientFactory].
 */
@Singleton
class GenAiClientProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prompts = LeasedClientCache<Unit, GenerativeModel>(
        create = { Generation.getClient() },
        dispose = { it.close() },
    )
    private val summarizers = LeasedClientCache<SummarizerKey, Summarizer>(
        create = { key ->
            Summarization.getClient(
                SummarizerOptions.builder(context)
                    .setInputType(key.inputType)
                    .setOutputType(key.outputType)
                    .setLanguage(key.language)
                    .setLongInputAutoTruncationEnabled(true)
                    .build(),
            )
        },
        dispose = { it.close() },
    )
    private val rewriters = LeasedClientCache<RewriterKey, Rewriter>(
        create = { key ->
            Rewriting.getClient(
                RewriterOptions.builder(context)
                    .setOutputType(key.outputType)
                    .setLanguage(key.language)
                    .build(),
            )
        },
        dispose = { it.close() },
    )
    private val proofreaders = LeasedClientCache<ProofreaderKey, Proofreader>(
        create = { key ->
            Proofreading.getClient(
                ProofreaderOptions.builder(context)
                    .setInputType(key.inputType)
                    .setLanguage(key.language)
                    .build(),
            )
        },
        dispose = { it.close() },
    )
    private val imageDescribers = LeasedClientCache<Unit, ImageDescriber>(
        create = {
            ImageDescription.getClient(ImageDescriberOptions.builder(context).build())
        },
        dispose = { it.close() },
    )

    fun promptModel(): ClientLease<GenerativeModel> = prompts.acquire(Unit)

    fun summarizer(inputType: Int, outputType: Int, language: Int): ClientLease<Summarizer> =
        summarizers.acquire(SummarizerKey(inputType, outputType, language))

    fun rewriter(outputType: Int, language: Int): ClientLease<Rewriter> =
        rewriters.acquire(RewriterKey(outputType, language))

    fun proofreader(inputType: Int, language: Int): ClientLease<Proofreader> =
        proofreaders.acquire(ProofreaderKey(inputType, language))

    fun imageDescriber(): ClientLease<ImageDescriber> = imageDescribers.acquire(Unit)

    /**
     * Retires a task's cached client, or all caches when null. Active operations
     * retain their leases; their callers cancel/join their own jobs separately.
     * Best-effort native cleanup must not crash ViewModel disposal or prevent
     * releasing other caches. A failed close never leaves a reusable stale handle.
     */
    fun close(task: AiTask?) {
        when (task) {
            null -> {
                runCatching { prompts.clear() }
                runCatching { summarizers.clear() }
                runCatching { rewriters.clear() }
                runCatching { proofreaders.clear() }
                runCatching { imageDescribers.clear() }
            }
            AiTask.ASK, AiTask.IMAGE_QUESTION -> runCatching { prompts.clear() }
            AiTask.SUMMARIZE -> runCatching { summarizers.clear() }
            AiTask.REWRITE -> runCatching { rewriters.clear() }
            AiTask.PROOFREAD -> runCatching { proofreaders.clear() }
            AiTask.IMAGE_DESCRIPTION -> runCatching { imageDescribers.clear() }
            // Speech owns its clients; OCR and Translate do not use this provider.
            AiTask.BASIC_TRANSCRIPTION, AiTask.ADVANCED_TRANSCRIPTION,
            AiTask.TEXT_RECOGNITION, AiTask.TRANSLATION -> Unit
        }
    }

    private data class SummarizerKey(val inputType: Int, val outputType: Int, val language: Int)
    private data class RewriterKey(val outputType: Int, val language: Int)
    private data class ProofreaderKey(val inputType: Int, val language: Int)
}
