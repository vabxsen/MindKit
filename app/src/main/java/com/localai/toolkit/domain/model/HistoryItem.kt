package com.localai.toolkit.domain.model

/** What produced a stored result. Drives the History filter chips and icons. */
enum class HistoryType {
    ASK,
    SUMMARY,
    REWRITE,
    PROOFREAD,
    OCR,
    TRANSLATION,
    IMAGE_DESCRIPTION,
    TRANSCRIPTION,
    DEVELOPER,
}

/**
 * One locally stored result.
 *
 * Only text is persisted. Imported images and audio are never copied into the app's
 * storage; [metadata] may record a short, non-identifying note such as the rewrite style
 * used, but never a file path that the app cannot guarantee remains readable.
 */
data class HistoryItem(
    val id: Long = 0L,
    val type: HistoryType,
    val title: String,
    val inputPreview: String,
    val output: String,
    val createdAtEpochMillis: Long,
    val metadata: String? = null,
)
