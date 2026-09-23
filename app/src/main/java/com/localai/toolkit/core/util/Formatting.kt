package com.localai.toolkit.core.util

import android.content.Context
import android.text.format.DateUtils
import com.localai.toolkit.R
import com.localai.toolkit.domain.model.HistoryType

/**
 * Relative timestamp such as "5 minutes ago".
 *
 * Uses the platform formatter so the result is already localised and already matches
 * what the user sees elsewhere on their device.
 */
fun formatRelativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )

/** The user-facing name of a history type. */
fun HistoryType.labelRes(): Int = when (this) {
    HistoryType.ASK -> R.string.history_type_ask
    HistoryType.SUMMARY -> R.string.history_type_summary
    HistoryType.REWRITE -> R.string.history_type_rewrite
    HistoryType.PROOFREAD -> R.string.history_type_proofread
    HistoryType.OCR -> R.string.history_type_ocr
    HistoryType.TRANSLATION -> R.string.history_type_translation
    HistoryType.IMAGE_DESCRIPTION -> R.string.history_type_image_description
    HistoryType.TRANSCRIPTION -> R.string.history_type_transcription
    HistoryType.DEVELOPER -> R.string.history_type_developer
}

fun HistoryType.label(context: Context): String = context.getString(labelRes())

/**
 * Shortens [text] for a list row or a stored preview.
 *
 * Collapses whitespace first so a multi-line paste does not produce a preview made
 * mostly of blank space.
 */
fun previewOf(text: String, maxChars: Int = 140): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars).trimEnd() + "…"
}

/**
 * Derives a short title from input text, for history rows.
 *
 * Prefers the first sentence, falling back to a truncated first line.
 */
fun titleOf(text: String, maxChars: Int = 60): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    if (collapsed.isEmpty()) return ""
    val sentenceEnd = collapsed.indexOfFirst { it == '.' || it == '?' || it == '!' }
    val candidate = if (sentenceEnd in 1 until maxChars) {
        collapsed.take(sentenceEnd + 1)
    } else {
        collapsed
    }
    return if (candidate.length <= maxChars) candidate else candidate.take(maxChars).trimEnd() + "…"
}
