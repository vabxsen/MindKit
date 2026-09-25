package com.localai.toolkit.di

import com.localai.toolkit.ai.capability.DefaultDeviceAiCapabilityManager
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.gemini.DefaultTranscriptionEngine
import com.localai.toolkit.ai.gemini.GeminiNanoAiEngine
import com.localai.toolkit.ai.gemini.TranscriptionEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Where the app chooses its on-device AI implementation.
 *
 * Only this file names a Gemini Nano type. Feature code depends on [AiEngine] and
 * [DeviceAiCapabilityManager], which is what lets the same screens run against
 * [com.localai.toolkit.ai.fake.FakeAiEngine] in unit tests, in Compose previews and on
 * emulators with no AICore.
 *
 * Note that binding the real engine does not assume the hardware supports anything:
 * [DefaultDeviceAiCapabilityManager] resolves availability at runtime, and an
 * unsupported device simply reports unsupported rather than failing to start.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    abstract fun bindSpeechClientFactory(impl: com.localai.toolkit.ai.gemini.MlKitSpeechClientFactory): com.localai.toolkit.ai.gemini.SpeechClientFactory

    @Binds
    abstract fun bindAudioDecoder(impl: com.localai.toolkit.ai.audio.AndroidAudioDecoder): com.localai.toolkit.ai.audio.AudioDecoder

    @Binds
    @Singleton
    abstract fun bindAiEngine(impl: GeminiNanoAiEngine): AiEngine

    @Binds
    @Singleton
    abstract fun bindTranscriptionEngine(impl: DefaultTranscriptionEngine): TranscriptionEngine

    @Binds
    @Singleton
    abstract fun bindCapabilityManager(
        impl: DefaultDeviceAiCapabilityManager,
    ): DeviceAiCapabilityManager
}
