package com.localai.toolkit.ai.gemini

import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.summarization.SummarizerOptions
import java.util.Locale

/**
 * Language selection for the GenAI feature APIs.
 *
 * Each feature supports a *different, small* set of languages - summarization currently
 * covers three, rewriting and proofreading seven. There is no API to enumerate them, so
 * the sets below mirror the constants declared on the options classes, and every lookup
 * falls back to English rather than passing a value the API does not define.
 *
 * This is also why the UI does not offer a language picker for these tools: it would
 * imply a choice the underlying model does not actually support.
 */
internal object GenAiLanguages {

    /** Summarization: English, Japanese and Korean only. */
    fun summarizationLanguage(locale: Locale = Locale.getDefault()): Int =
        when (locale.language.lowercase(Locale.ROOT)) {
            "ja" -> SummarizerOptions.Language.JAPANESE
            "ko" -> SummarizerOptions.Language.KOREAN
            else -> SummarizerOptions.Language.ENGLISH
        }

    /** Rewriting: English, Japanese, Korean, German, French, Italian and Spanish. */
    fun rewritingLanguage(locale: Locale = Locale.getDefault()): Int =
        when (locale.language.lowercase(Locale.ROOT)) {
            "ja" -> RewriterOptions.Language.JAPANESE
            "ko" -> RewriterOptions.Language.KOREAN
            "de" -> RewriterOptions.Language.GERMAN
            "fr" -> RewriterOptions.Language.FRENCH
            "it" -> RewriterOptions.Language.ITALIAN
            "es" -> RewriterOptions.Language.SPANISH
            else -> RewriterOptions.Language.ENGLISH
        }

    /** Proofreading: the same seven languages as rewriting. */
    fun proofreadingLanguage(locale: Locale = Locale.getDefault()): Int =
        when (locale.language.lowercase(Locale.ROOT)) {
            "ja" -> ProofreaderOptions.Language.JAPANESE
            "ko" -> ProofreaderOptions.Language.KOREAN
            "de" -> ProofreaderOptions.Language.GERMAN
            "fr" -> ProofreaderOptions.Language.FRENCH
            "it" -> ProofreaderOptions.Language.ITALIAN
            "es" -> ProofreaderOptions.Language.SPANISH
            else -> ProofreaderOptions.Language.ENGLISH
        }

    /** True when the device language is one summarization actually handles. */
    fun isSummarizationLanguageSupported(locale: Locale = Locale.getDefault()): Boolean =
        locale.language.lowercase(Locale.ROOT) in setOf("en", "ja", "ko")

    /** True when the device language is one rewriting and proofreading handle. */
    fun isRewritingLanguageSupported(locale: Locale = Locale.getDefault()): Boolean =
        locale.language.lowercase(Locale.ROOT) in setOf("en", "ja", "ko", "de", "fr", "it", "es")
}
