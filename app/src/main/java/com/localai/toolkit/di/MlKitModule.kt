package com.localai.toolkit.di

import com.localai.toolkit.ai.mlkit.MlKitOcrEngine
import com.localai.toolkit.ai.mlkit.MlKitTranslationEngine
import com.localai.toolkit.ai.mlkit.OcrEngine
import com.localai.toolkit.ai.mlkit.TranslationEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The ML Kit backed engines.
 *
 * These are separate from [AiModule] because they are not Gemini Nano: text recognition
 * and translation work on essentially any supported device, and they must keep working
 * on hardware where the GenAI features do not exist.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MlKitModule {

    @Binds
    @Singleton
    abstract fun bindOcrEngine(impl: MlKitOcrEngine): OcrEngine

    @Binds
    @Singleton
    abstract fun bindTranslationEngine(impl: MlKitTranslationEngine): TranslationEngine
}
