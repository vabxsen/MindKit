package com.localai.toolkit.ai.engine

import android.graphics.Bitmap

/** Summary shape the user asked for. Mapped onto whatever the engine actually supports. */
enum class SummaryLength { SHORT, MEDIUM, DETAILED }

/** What kind of text is being summarised. */
enum class SummaryInputType { ARTICLE, CONVERSATION }

/** Rewrite styles offered by the product. */
enum class RewriteStyle { REPHRASE, PROFESSIONAL, FRIENDLY, SHORTEN, ELABORATE, EMOJIFY }

/** Where the proofread text came from, which changes the correction model's expectations. */
enum class ProofreadInputType { KEYBOARD, VOICE }

data class AskRequest(
    val prompt: String,
    /** Prior turns, oldest first. Kept purely on device. */
    val history: List<AskTurn> = emptyList(),
    val systemInstruction: String? = null,
)

data class AskTurn(val role: AskRole, val text: String)

enum class AskRole { USER, MODEL }

data class SummarizeRequest(
    val text: String,
    val length: SummaryLength = SummaryLength.MEDIUM,
    val inputType: SummaryInputType = SummaryInputType.ARTICLE,
)

data class RewriteRequest(
    val text: String,
    val style: RewriteStyle = RewriteStyle.REPHRASE,
)

data class ProofreadRequest(
    val text: String,
    val inputType: ProofreadInputType = ProofreadInputType.KEYBOARD,
)

data class DescribeImageRequest(val bitmap: Bitmap)

data class AnalyzeImageRequest(val bitmap: Bitmap, val prompt: String)

/**
 * A single streamed step of a generation.
 *
 * [text] is always the full text produced so far, not just the delta, so collectors can
 * render it directly without accumulating state of their own.
 */
data class AiStreamChunk(val text: String, val isFinal: Boolean)

/** Progress of an on-device model download. */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Started(val totalBytes: Long?) : ModelDownloadState
    data class InProgress(val downloadedBytes: Long, val totalBytes: Long?) : ModelDownloadState {
        /** 0f..1f, or null when the API did not report a total. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0L }
                ?.let { (downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    }
    data object Completed : ModelDownloadState
    data class Failed(val reason: com.localai.toolkit.domain.model.AiFailure) : ModelDownloadState
}
