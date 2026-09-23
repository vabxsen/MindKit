package com.localai.toolkit.ai.fake

import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [DeviceAiCapabilityManager] whose answers are supplied rather than measured.
 *
 * Lets tests and Compose previews pin the device to any combination of supported,
 * unsupported and download-required without needing that hardware.
 */
class FakeCapabilityManager(
    initial: Map<AiTask, AiCapability> = supportedDevice(),
) : DeviceAiCapabilityManager {

    private val state = MutableStateFlow(
        DeviceAiSnapshot(capabilities = initial, lastCheckedAtEpochMillis = 0L),
    )

    override val snapshot: StateFlow<DeviceAiSnapshot> = state.asStateFlow()

    var refreshCount: Int = 0
        private set

    override suspend fun refresh(force: Boolean) {
        refreshCount++
    }

    override suspend fun refresh(task: AiTask) {
        refreshCount++
    }

    /** Overrides one capability, e.g. to simulate a model download completing. */
    fun set(task: AiTask, capability: AiCapability) {
        state.value = state.value.copy(
            capabilities = state.value.capabilities + (task to capability),
        )
    }

    companion object {

        /** The provider that would normally serve each task on real hardware. */
        fun defaultProvider(task: AiTask): AiProvider = when (task) {
            AiTask.ASK,
            AiTask.SUMMARIZE,
            AiTask.REWRITE,
            AiTask.PROOFREAD,
            AiTask.IMAGE_DESCRIPTION,
            AiTask.IMAGE_QUESTION,
            AiTask.ADVANCED_TRANSCRIPTION,
            -> AiProvider.GEMINI_NANO

            AiTask.TEXT_RECOGNITION, AiTask.TRANSLATION -> AiProvider.ML_KIT
            AiTask.BASIC_TRANSCRIPTION -> AiProvider.ANDROID_PLATFORM
        }

        /** Every GenAI feature ready. Represents a current Gemini Nano capable device. */
        fun supportedDevice(): Map<AiTask, AiCapability> = AiTask.entries.associateWith { task ->
            val provider = defaultProvider(task)
            AiCapability(
                task = task,
                status = AiCapabilityStatus.AVAILABLE,
                provider = provider,
                baseModelName = if (provider == AiProvider.GEMINI_NANO) {
                    "gemini-nano-sample"
                } else {
                    null
                },
            )
        }

        /** No Gemini Nano. ML Kit and platform backed features still work. */
        fun unsupportedDevice(): Map<AiTask, AiCapability> = AiTask.entries.associateWith { task ->
            val provider = defaultProvider(task)
            if (provider == AiProvider.GEMINI_NANO) {
                AiCapability.unsupported(task, detail = "AICore not present")
            } else {
                AiCapability(task, AiCapabilityStatus.AVAILABLE, provider)
            }
        }

        /** Gemini Nano supported, but feature models still have to be fetched. */
        fun downloadRequiredDevice(): Map<AiTask, AiCapability> =
            AiTask.entries.associateWith { task ->
                val provider = defaultProvider(task)
                AiCapability(
                    task = task,
                    status = if (provider == AiProvider.GEMINI_NANO) {
                        AiCapabilityStatus.DOWNLOADABLE
                    } else {
                        AiCapabilityStatus.AVAILABLE
                    },
                    provider = provider,
                )
            }
    }
}
