package com.localai.toolkit.ai.fake

import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.AiStreamChunk
import com.localai.toolkit.ai.engine.AnalyzeImageRequest
import com.localai.toolkit.ai.engine.AskRequest
import com.localai.toolkit.ai.engine.DescribeImageRequest
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.engine.ProofreadRequest
import com.localai.toolkit.ai.engine.RewriteRequest
import com.localai.toolkit.ai.engine.RewriteStyle
import com.localai.toolkit.ai.engine.SummarizeRequest
import com.localai.toolkit.ai.engine.SummaryLength
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A deterministic [AiEngine] that needs no Gemini Nano, no AICore and no network.
 *
 * It exists for three reasons, all of them load-bearing:
 *  - unit tests can drive every ViewModel without hardware,
 *  - Compose previews can render real success/loading/error states,
 *  - the app stays demonstrable on an emulator.
 *
 * It never pretends to be the real model in the UI: whatever binds this engine is also
 * responsible for reporting [com.localai.toolkit.domain.model.AiProvider.FAKE].
 *
 * @param failWith when set, every call fails with this failure instead of succeeding.
 * @param chunkDelayMillis pause between streamed chunks; leave at 0 in tests.
 */
class FakeAiEngine(
    private val failWith: AiFailure? = null,
    private val chunkDelayMillis: Long = 0L,
    private val downloadSteps: Int = 5,
) : AiEngine {

    private val released = mutableSetOf<AiTask>()

    /** Tasks that [release] has been called for. Asserted on in tests. */
    val releasedTasks: Set<AiTask> get() = released.toSet()

    override fun generateText(request: AskRequest): Flow<AiStreamChunk> =
        streamOf("This is a sample on-device answer to: " + request.prompt.trim())

    override fun analyzeImage(request: AnalyzeImageRequest): Flow<AiStreamChunk> =
        streamOf("Sample image answer for: " + request.prompt.trim())

    override suspend fun summarize(request: SummarizeRequest): String {
        failWith?.let { throw AiException(it) }
        val bullets = when (request.length) {
            SummaryLength.SHORT -> 1
            SummaryLength.MEDIUM -> 2
            SummaryLength.DETAILED -> 3
        }
        val subject = request.text.trim().take(120).ifBlank { "the supplied text" }
        return (1..bullets).joinToString(separator = System.lineSeparator()) { index ->
            if (index == 1) "- Key point about " + subject else "- Additional point " + index
        }
    }

    override suspend fun rewrite(request: RewriteRequest): String {
        failWith?.let { throw AiException(it) }
        val prefix = when (request.style) {
            RewriteStyle.REPHRASE -> "Rephrased"
            RewriteStyle.PROFESSIONAL -> "Professional"
            RewriteStyle.FRIENDLY -> "Friendly"
            RewriteStyle.SHORTEN -> "Shortened"
            RewriteStyle.ELABORATE -> "Elaborated"
            RewriteStyle.EMOJIFY -> "Emojified"
        }
        return prefix + ": " + request.text.trim()
    }

    override suspend fun proofread(request: ProofreadRequest): String {
        failWith?.let { throw AiException(it) }
        // Deterministic, obviously synthetic correction: collapse runs of spaces and
        // capitalise the opening character.
        return request.text.trim()
            .replace(Regex(" +"), " ")
            .replaceFirstChar { it.uppercaseChar() }
    }

    override suspend fun describeImage(request: DescribeImageRequest): String {
        failWith?.let { throw AiException(it) }
        return "A sample description of a " + request.bitmap.width + " by " +
            request.bitmap.height + " pixel image."
    }

    override fun downloadModel(task: AiTask): Flow<ModelDownloadState> = flow {
        val failure = failWith
        if (failure != null) {
            emit(ModelDownloadState.Failed(failure))
            return@flow
        }
        val total = 40L * 1024 * 1024
        emit(ModelDownloadState.Started(total))
        repeat(downloadSteps) { step ->
            delay(chunkDelayMillis)
            emit(ModelDownloadState.InProgress(total * (step + 1) / downloadSteps, total))
        }
        emit(ModelDownloadState.Completed)
    }

    override fun release(task: AiTask?) {
        if (task == null) released.addAll(AiTask.entries) else released.add(task)
    }

    /** Emits cumulative text so collectors can render each emission directly. */
    private fun streamOf(full: String): Flow<AiStreamChunk> = flow {
        failWith?.let { throw AiException(it) }
        val words = full.split(" ")
        if (words.isEmpty()) {
            emit(AiStreamChunk("", isFinal = true))
            return@flow
        }
        val builder = StringBuilder()
        words.forEachIndexed { index, word ->
            if (index > 0) builder.append(" ")
            builder.append(word)
            delay(chunkDelayMillis)
            emit(AiStreamChunk(builder.toString(), isFinal = index == words.lastIndex))
        }
    }
}
