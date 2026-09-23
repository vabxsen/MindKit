package com.localai.toolkit.domain.model

/**
 * A resolved answer to "can this device do X right now?".
 *
 * @param task the capability being described.
 * @param status the runtime status.
 * @param provider which engine answered.
 * @param baseModelName model identifier when the underlying API exposes one.
 * @param detail short machine-readable reason, shown only in developer/debug surfaces.
 */
data class AiCapability(
    val task: AiTask,
    val status: AiCapabilityStatus,
    val provider: AiProvider,
    val baseModelName: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun unknown(task: AiTask): AiCapability =
            AiCapability(task, AiCapabilityStatus.UNKNOWN, AiProvider.NONE)

        fun unsupported(task: AiTask, detail: String? = null): AiCapability =
            AiCapability(task, AiCapabilityStatus.UNSUPPORTED, AiProvider.NONE, detail = detail)
    }
}

/** The full picture of what this device can do, as one immutable value. */
data class DeviceAiSnapshot(
    val capabilities: Map<AiTask, AiCapability> = emptyMap(),
    val isRefreshing: Boolean = false,
    val lastCheckedAtEpochMillis: Long? = null,
) {
    operator fun get(task: AiTask): AiCapability =
        capabilities[task] ?: AiCapability.unknown(task)

    /** True when at least one Gemini Nano backed capability is ready to use. */
    val hasReadyGenAi: Boolean
        get() = capabilities.values.any {
            it.provider == AiProvider.GEMINI_NANO && it.status == AiCapabilityStatus.AVAILABLE
        }

    /** True when Gemini Nano is supported but something still has to be downloaded. */
    val genAiNeedsDownload: Boolean
        get() = capabilities.values.any {
            it.provider == AiProvider.GEMINI_NANO &&
                (it.status == AiCapabilityStatus.DOWNLOADABLE || it.status == AiCapabilityStatus.DOWNLOADING)
        }

    val genAiSupportedAtAll: Boolean
        get() = capabilities.values.any {
            it.provider == AiProvider.GEMINI_NANO &&
                it.status != AiCapabilityStatus.UNSUPPORTED &&
                it.status != AiCapabilityStatus.UNKNOWN
        }
}
