package com.localai.toolkit.ai.engine

import com.localai.toolkit.domain.model.AiTask
import kotlinx.coroutines.flow.Flow

/**
 * The single seam between feature code and whichever on-device model actually runs.
 *
 * No UI or ViewModel imports an ML Kit or AICore type; everything goes through here.
 * That is what lets the whole app be exercised on an emulator, in unit tests and in
 * Compose previews through [com.localai.toolkit.ai.fake.FakeAiEngine].
 *
 * Every method throws [com.localai.toolkit.domain.model.AiException] on failure, carrying
 * a mapped [com.localai.toolkit.domain.model.AiFailure] rather than a vendor error code.
 */
interface AiEngine {

    /**
     * Streams a response to a free-form prompt.
     *
     * Each emission carries the full text generated so far. The flow completes when
     * generation finishes and is cancelled if the collector goes away, which also
     * cancels the underlying inference.
     */
    fun generateText(request: AskRequest): Flow<AiStreamChunk>

    /** Streams a multimodal answer about [AnalyzeImageRequest.bitmap]. */
    fun analyzeImage(request: AnalyzeImageRequest): Flow<AiStreamChunk>

    suspend fun summarize(request: SummarizeRequest): String

    suspend fun rewrite(request: RewriteRequest): String

    suspend fun proofread(request: ProofreadRequest): String

    suspend fun describeImage(request: DescribeImageRequest): String

    /**
     * Downloads the model backing [task].
     *
     * Emits progress until it terminates with [ModelDownloadState.Completed] or
     * [ModelDownloadState.Failed]; it does not throw.
     */
    fun downloadModel(task: AiTask): Flow<ModelDownloadState>

    /**
     * Releases any inference resources held for [task], or for every task when null.
     *
     * Called when a feature screen leaves the composition so that a backgrounded app is
     * not holding an AICore session open.
     */
    fun release(task: AiTask? = null)
}
