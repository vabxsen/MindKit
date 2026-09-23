package com.localai.toolkit.domain.model

/**
 * The unit of on-device capability the app reasons about.
 *
 * Tasks are deliberately decoupled from the tools in the UI: several tools can share a
 * task (Extract Text and Image AI both surface [TEXT_RECOGNITION]) and a single tool can
 * depend on more than one.
 */
enum class AiTask {
    /** Free-form prompting. Backed by the ML Kit GenAI Prompt API (Gemini Nano). */
    ASK,

    /** Abstractive summarisation. ML Kit GenAI Summarization. */
    SUMMARIZE,

    /** Style transfer over existing text. ML Kit GenAI Rewriting. */
    REWRITE,

    /** Grammar and spelling correction. ML Kit GenAI Proofreading. */
    PROOFREAD,

    /** Natural-language description of a bitmap. ML Kit GenAI Image Description. */
    IMAGE_DESCRIPTION,

    /** Multimodal prompting over an image. Requires the Prompt API plus image support. */
    IMAGE_QUESTION,

    /** GenAI speech-to-text. Distinct from the platform SpeechRecognizer. */
    ADVANCED_TRANSCRIPTION,

    /** Platform on-device speech recognition. Available far more widely than GenAI. */
    BASIC_TRANSCRIPTION,

    /** ML Kit Text Recognition v2. Bundled, so it works with no download and no network. */
    TEXT_RECOGNITION,

    /** ML Kit on-device translation. Requires a per-language-pair model download. */
    TRANSLATION,
}
