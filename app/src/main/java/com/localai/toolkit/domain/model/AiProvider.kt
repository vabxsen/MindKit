package com.localai.toolkit.domain.model

/** Which on-device engine backs a capability. Surfaced in the UI so claims stay honest. */
enum class AiProvider {
    /** Gemini Nano through AICore, reached via the ML Kit GenAI APIs. */
    GEMINI_NANO,

    /** A bundled or downloadable ML Kit model that is not Gemini Nano. */
    ML_KIT,

    /** A platform service such as android.speech.SpeechRecognizer. */
    ANDROID_PLATFORM,

    /** Deterministic local code - no model involved. */
    ON_DEVICE_ALGORITHM,

    /** Substituted engine used by tests and Compose previews. */
    FAKE,

    /** Nothing can serve this capability here. */
    NONE,
}
