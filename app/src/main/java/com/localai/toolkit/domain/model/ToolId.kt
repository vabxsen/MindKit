package com.localai.toolkit.domain.model

/**
 * The tools shown on Home.
 *
 * Adding a tool means adding an entry here, a route in
 * [com.localai.toolkit.core.navigation.Destination] and a screen. Home renders itself
 * from this list, so nothing else has to change.
 */
enum class ToolId(
    /** Capability that decides whether the tool is usable, or null when always usable. */
    val requiredTask: AiTask?,
) {
    ASK(AiTask.ASK),
    SUMMARIZE(AiTask.SUMMARIZE),
    REWRITE(AiTask.REWRITE),
    PROOFREAD(AiTask.PROOFREAD),
    OCR(AiTask.TEXT_RECOGNITION),
    TRANSLATE(AiTask.TRANSLATION),
    IMAGE(AiTask.IMAGE_DESCRIPTION),
    TRANSCRIBE(AiTask.BASIC_TRANSCRIPTION),
    DEVELOPER(null),
}
