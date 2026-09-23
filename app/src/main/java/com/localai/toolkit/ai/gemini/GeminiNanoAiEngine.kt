package com.localai.toolkit.ai.gemini

import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.AiStreamChunk
import com.localai.toolkit.ai.engine.AnalyzeImageRequest
import com.localai.toolkit.ai.engine.AskRequest
import com.localai.toolkit.ai.engine.AskRole
import com.localai.toolkit.ai.engine.DescribeImageRequest
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.engine.ProofreadInputType
import com.localai.toolkit.ai.engine.ProofreadRequest
import com.localai.toolkit.ai.engine.RewriteRequest
import com.localai.toolkit.ai.engine.RewriteStyle
import com.localai.toolkit.ai.engine.SummarizeRequest
import com.localai.toolkit.ai.engine.SummaryInputType
import com.localai.toolkit.ai.engine.SummaryLength
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiTask
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/**
 * The real on-device engine: Gemini Nano through the ML Kit GenAI APIs.
 *
 * Nothing in here is reachable from a screen except via [AiEngine], which is what allows
 * the whole app to run against [com.localai.toolkit.ai.fake.FakeAiEngine] on hardware
 * that has no Gemini Nano at all.
 *
 * Every call funnels its failures through [toAiFailure], so a caller only ever sees an
 * [AiException] carrying a mapped reason - never a vendor error code and never a crash.
 */
@Singleton
class GeminiNanoAiEngine @Inject constructor(
    private val clients: GenAiClientProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AiEngine {

    override fun generateText(request: AskRequest): Flow<AiStreamChunk> =
        promptStream(buildPromptRequest(request))

    override fun analyzeImage(request: AnalyzeImageRequest): Flow<AiStreamChunk> =
        promptStream(
            generateContentRequest(
                ImagePart(request.bitmap),
                TextPart(request.prompt),
            ) {},
        )

    /**
     * Shared streaming path for both text and multimodal prompts.
     *
     * The ML Kit stream emits each candidate as it grows; the app's contract is that a
     * chunk carries the full text so far, so partials are accumulated here rather than in
     * every collector. The final emission is flagged so the UI can stop showing a caret.
     */
    private fun promptStream(request: GenerateContentRequest): Flow<AiStreamChunk> = flow {
        val model = clients.promptModel()
        val builder = StringBuilder()

        model.generateContentStream(request).collect { response ->
            val text = response.candidates.firstOrNull()?.text.orEmpty()
            if (text.isEmpty()) return@collect
            builder.append(text)
            emit(AiStreamChunk(builder.toString(), isFinal = false))
        }

        emit(AiStreamChunk(builder.toString(), isFinal = true))
    }
        .flowOn(ioDispatcher)
        .catch { throwable -> throw AiException(throwable.toAiFailure(), throwable) }

    /**
     * Folds prior turns into a single prompt.
     *
     * The Prompt API takes content rather than a managed chat session, so conversation
     * memory is the app's responsibility. History never leaves the device: it is held in
     * the Ask AI ViewModel and re-sent to the local model on each turn.
     */
    private fun buildPromptRequest(request: AskRequest): GenerateContentRequest {
        val prompt = if (request.history.isEmpty()) {
            request.prompt
        } else {
            buildString {
                request.history.forEach { turn ->
                    append(if (turn.role == AskRole.USER) "User: " else "Assistant: ")
                    append(turn.text)
                    append("\n\n")
                }
                append("User: ")
                append(request.prompt)
            }
        }
        return generateContentRequest(TextPart(prompt)) {}
    }

    override suspend fun summarize(request: SummarizeRequest): String =
        withContext(ioDispatcher) {
            runMapped {
                val summarizer = clients.summarizer(
                    inputType = request.inputType.toMlKitInputType(),
                    outputType = request.length.toMlKitOutputType(),
                    language = GenAiLanguages.summarizationLanguage(),
                )
                summarizer.prepareInferenceEngine().await()
                val result = summarizer
                    .runInference(SummarizationRequest.builder(request.text).build())
                    .await()
                result.summary
            }
        }

    override suspend fun rewrite(request: RewriteRequest): String = withContext(ioDispatcher) {
        runMapped {
            val rewriter = clients.rewriter(
                outputType = request.style.toMlKitOutputType(),
                language = GenAiLanguages.rewritingLanguage(),
            )
            rewriter.prepareInferenceEngine().await()
            val result = rewriter
                .runInference(RewritingRequest.builder(request.text).build())
                .await()
            // The API returns ranked suggestions; the first is the one to show, and an
            // empty list means the model declined rather than failed.
            result.results.firstOrNull()?.text.orEmpty()
        }
    }

    override suspend fun proofread(request: ProofreadRequest): String =
        withContext(ioDispatcher) {
            runMapped {
                val proofreader = clients.proofreader(
                    inputType = request.inputType.toMlKitInputType(),
                    language = GenAiLanguages.proofreadingLanguage(),
                )
                proofreader.prepareInferenceEngine().await()
                val result = proofreader
                    .runInference(ProofreadingRequest.builder(request.text).build())
                    .await()
                result.results.firstOrNull()?.text.orEmpty()
            }
        }

    override suspend fun describeImage(request: DescribeImageRequest): String =
        withContext(ioDispatcher) {
            runMapped {
                val describer = clients.imageDescriber()
                describer.prepareInferenceEngine().await()
                describer
                    .runInference(ImageDescriptionRequest.builder(request.bitmap).build())
                    .await()
                    .description
            }
        }

    override fun downloadModel(task: AiTask): Flow<ModelDownloadState> = when (task) {
        // The Prompt API exposes a Flow of DownloadStatus directly.
        AiTask.ASK, AiTask.IMAGE_QUESTION -> clients.promptModel().download()
            .map { it.toDownloadState() }
            .catch { throwable -> emit(ModelDownloadState.Failed(throwable.toAiFailure())) }
            .flowOn(ioDispatcher)

        AiTask.SUMMARIZE -> featureDownload { callback ->
            clients.summarizer(
                inputType = SummarizerOptions.InputType.ARTICLE,
                outputType = SummarizerOptions.OutputType.TWO_BULLETS,
                language = GenAiLanguages.summarizationLanguage(),
            ).downloadFeature(callback).await()
        }

        AiTask.REWRITE -> featureDownload { callback ->
            clients.rewriter(
                outputType = RewriterOptions.OutputType.REPHRASE,
                language = GenAiLanguages.rewritingLanguage(),
            ).downloadFeature(callback).await()
        }

        AiTask.PROOFREAD -> featureDownload { callback ->
            clients.proofreader(
                inputType = ProofreaderOptions.InputType.KEYBOARD,
                language = GenAiLanguages.proofreadingLanguage(),
            ).downloadFeature(callback).await()
        }

        AiTask.IMAGE_DESCRIPTION -> featureDownload { callback ->
            clients.imageDescriber().downloadFeature(callback).await()
        }

        // Not GenAI-backed, so there is nothing for this engine to download.
        AiTask.TEXT_RECOGNITION,
        AiTask.TRANSLATION,
        AiTask.BASIC_TRANSCRIPTION,
        AiTask.ADVANCED_TRANSCRIPTION,
        -> flow { emit(ModelDownloadState.Failed(com.localai.toolkit.domain.model.AiFailure.Unsupported())) }
    }

    /**
     * Wraps the callback-based feature download APIs as a Flow.
     *
     * The download itself reports through [DownloadCallback]; the returned future only
     * signals completion. Both are bridged here so callers see one progress stream that
     * ends in Completed or Failed and never throws.
     */
    private fun featureDownload(
        start: suspend (DownloadCallback) -> Unit,
    ): Flow<ModelDownloadState> = callbackFlow {
        var totalBytes: Long? = null
        // Whether the callback already delivered a terminal event. Without this, the
        // fallback below would emit a second Completed after a genuine failure.
        var terminated = false

        val callback = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) {
                totalBytes = bytesToDownload.takeIf { it > 0 }
                trySend(ModelDownloadState.Started(totalBytes))
            }

            override fun onDownloadProgress(totalBytesDownloaded: Long) {
                trySend(ModelDownloadState.InProgress(totalBytesDownloaded, totalBytes))
            }

            override fun onDownloadCompleted() {
                terminated = true
                trySend(ModelDownloadState.Completed)
            }

            override fun onDownloadFailed(e: GenAiException) {
                terminated = true
                trySend(ModelDownloadState.Failed(e.toAiFailure()))
            }
        }

        try {
            // Suspends until the download future resolves.
            start(callback)
            // A model that is already present can resolve the future without ever firing
            // a terminal callback, so the flow still has to finish rather than leaving
            // the progress UI spinning.
            if (!terminated) trySend(ModelDownloadState.Completed)
        } catch (e: Exception) {
            if (!terminated) trySend(ModelDownloadState.Failed(e.toAiFailure()))
        }
        channel.close()

        awaitClose { }
    }.flowOn(ioDispatcher)

    override fun release(task: AiTask?) {
        clients.close(task)
    }

    /** Runs [block], converting any ML Kit failure into a mapped [AiException]. */
    private inline fun <T> runMapped(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw AiException(e.toAiFailure(), e)
    }
}

internal fun DownloadStatus.toDownloadState(): ModelDownloadState = when (this) {
    is DownloadStatus.DownloadStarted -> ModelDownloadState.Started(
        bytesToDownload.takeIf { it > 0 },
    )
    is DownloadStatus.DownloadProgress -> ModelDownloadState.InProgress(
        downloadedBytes = totalBytesDownloaded,
        // The progress callback does not repeat the total, so a percentage is only
        // available once a Started event has supplied one.
        totalBytes = null,
    )
    is DownloadStatus.DownloadCompleted -> ModelDownloadState.Completed
    // The failure is carried in a property named `e` on the ML Kit type.
    is DownloadStatus.DownloadFailed -> ModelDownloadState.Failed(e.toAiFailure())
}

/*
 * Product option to API constant.
 *
 * Summarization is the interesting one: the API's only output control is how many bullet
 * points to produce, so "Short / Medium / Detailed" maps to one, two or three bullets.
 * The UI says exactly that rather than implying a prose length control the model does
 * not have.
 */
private fun SummaryLength.toMlKitOutputType(): Int = when (this) {
    SummaryLength.SHORT -> SummarizerOptions.OutputType.ONE_BULLET
    SummaryLength.MEDIUM -> SummarizerOptions.OutputType.TWO_BULLETS
    SummaryLength.DETAILED -> SummarizerOptions.OutputType.THREE_BULLETS
}

private fun SummaryInputType.toMlKitInputType(): Int = when (this) {
    SummaryInputType.ARTICLE -> SummarizerOptions.InputType.ARTICLE
    SummaryInputType.CONVERSATION -> SummarizerOptions.InputType.CONVERSATION
}

private fun RewriteStyle.toMlKitOutputType(): Int = when (this) {
    RewriteStyle.REPHRASE -> RewriterOptions.OutputType.REPHRASE
    RewriteStyle.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
    RewriteStyle.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
    RewriteStyle.SHORTEN -> RewriterOptions.OutputType.SHORTEN
    RewriteStyle.ELABORATE -> RewriterOptions.OutputType.ELABORATE
    RewriteStyle.EMOJIFY -> RewriterOptions.OutputType.EMOJIFY
}

private fun ProofreadInputType.toMlKitInputType(): Int = when (this) {
    ProofreadInputType.KEYBOARD -> ProofreaderOptions.InputType.KEYBOARD
    ProofreadInputType.VOICE -> ProofreaderOptions.InputType.VOICE
}
