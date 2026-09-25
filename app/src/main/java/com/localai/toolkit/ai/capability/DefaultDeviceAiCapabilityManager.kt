package com.localai.toolkit.ai.capability

import android.content.Context
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.localai.toolkit.ai.gemini.GenAiClientProvider
import com.localai.toolkit.ai.gemini.GenAiLanguages
import com.localai.toolkit.ai.gemini.TranscriptionEngine
import com.localai.toolkit.ai.gemini.TranscriptionMode
import com.localai.toolkit.ai.gemini.toAiFailure
import com.localai.toolkit.ai.gemini.toCapabilityStatus
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await

/**
 * Resolves what this device can actually do, at runtime.
 *
 * Deliberately never infers support from Build.MANUFACTURER or Build.MODEL. Gemini Nano
 * availability depends on the device, its system image, the AICore version installed and
 * which feature models have been pushed to it - none of which a model string predicts.
 * Every GenAI answer here comes from that feature's own status call.
 *
 * Results are cached for one minute so that opening Home, then a tool, then
 * the capability screen does not re-probe AICore three times.
 */
@Singleton
class DefaultDeviceAiCapabilityManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clients: GenAiClientProvider,
    private val transcriptionEngine: TranscriptionEngine,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DeviceAiCapabilityManager {

    private val cache = CapabilitySnapshotCache(ioDispatcher, ::resolve)
    override val snapshot: StateFlow<DeviceAiSnapshot> = cache.snapshot

    override suspend fun refresh(force: Boolean) = cache.refresh(force)

    override suspend fun refresh(task: AiTask) = cache.refresh(task)

    private suspend fun resolve(task: AiTask): AiCapability = when (task) {
        AiTask.ASK, AiTask.IMAGE_QUESTION -> resolvePrompt(task)
        AiTask.SUMMARIZE -> resolveSummarization()
        AiTask.REWRITE -> resolveRewriting()
        AiTask.PROOFREAD -> resolveProofreading()
        AiTask.IMAGE_DESCRIPTION -> resolveImageDescription()
        AiTask.ADVANCED_TRANSCRIPTION -> resolveAdvancedTranscription()
        AiTask.BASIC_TRANSCRIPTION -> resolveBasicTranscription()
        AiTask.TEXT_RECOGNITION -> resolveTextRecognition()
        AiTask.TRANSLATION -> resolveTranslation()
    }

    /**
     * Prompt API status, used for both text prompting and image questions.
     *
     * Image questions ride on the same Prompt client, so they share its status: there is
     * no separate multimodal availability call to consult.
     */
    private suspend fun resolvePrompt(task: AiTask): AiCapability = genAi(task) {
        clients.promptModel().use { lease ->
            val model = lease.client
            val status = model.checkStatus().toCapabilityStatus()

            AiCapability(
                task = task,
                status = status,
                provider = AiProvider.GEMINI_NANO,
                baseModelName = if (status == AiCapabilityStatus.AVAILABLE) {
                    optionalModelName { model.getBaseModelName() }
                } else {
                    null
                },
            )
        }
    }

    private suspend fun resolveSummarization(): AiCapability = genAi(AiTask.SUMMARIZE) {
        // Summarization only handles English, Japanese and Korean. Reporting AVAILABLE
        // for a device set to, say, Polish would be a claim the model cannot honour.
        if (!GenAiLanguages.isSummarizationLanguageSupported()) {
            return@genAi AiCapability(
                task = AiTask.SUMMARIZE,
                status = AiCapabilityStatus.UNSUPPORTED,
                provider = AiProvider.GEMINI_NANO,
                detail = "Device language not supported by the summarization model",
            )
        }
        clients.summarizer(
            inputType = SummarizerOptions.InputType.ARTICLE,
            outputType = SummarizerOptions.OutputType.TWO_BULLETS,
            language = GenAiLanguages.summarizationLanguage(),
        ).use { lease ->
            val status = lease.client.checkFeatureStatus().await().toCapabilityStatus()
            AiCapability(
                task = AiTask.SUMMARIZE,
                status = status,
                provider = AiProvider.GEMINI_NANO,
                baseModelName = lease.client.baseModelNameOrNull(status),
            )
        }
    }

    private suspend fun resolveRewriting(): AiCapability = genAi(AiTask.REWRITE) {
        if (!GenAiLanguages.isRewritingLanguageSupported()) {
            return@genAi AiCapability(
                task = AiTask.REWRITE,
                status = AiCapabilityStatus.UNSUPPORTED,
                provider = AiProvider.GEMINI_NANO,
                detail = "Device language not supported by the rewriting model",
            )
        }
        clients.rewriter(
            outputType = RewriterOptions.OutputType.REPHRASE,
            language = GenAiLanguages.rewritingLanguage(),
        ).use { lease ->
            val status = lease.client.checkFeatureStatus().await().toCapabilityStatus()
            AiCapability(
                task = AiTask.REWRITE,
                status = status,
                provider = AiProvider.GEMINI_NANO,
                baseModelName = lease.client.baseModelNameOrNull(status),
            )
        }
    }

    private suspend fun resolveProofreading(): AiCapability = genAi(AiTask.PROOFREAD) {
        if (!GenAiLanguages.isRewritingLanguageSupported()) {
            return@genAi AiCapability(
                task = AiTask.PROOFREAD,
                status = AiCapabilityStatus.UNSUPPORTED,
                provider = AiProvider.GEMINI_NANO,
                detail = "Device language not supported by the proofreading model",
            )
        }
        clients.proofreader(
            inputType = ProofreaderOptions.InputType.KEYBOARD,
            language = GenAiLanguages.proofreadingLanguage(),
        ).use { lease ->
            val status = lease.client.checkFeatureStatus().await().toCapabilityStatus()
            AiCapability(
                task = AiTask.PROOFREAD,
                status = status,
                provider = AiProvider.GEMINI_NANO,
                baseModelName = lease.client.baseModelNameOrNull(status),
            )
        }
    }

    private suspend fun resolveImageDescription(): AiCapability =
        genAi(AiTask.IMAGE_DESCRIPTION) {
            clients.imageDescriber().use { lease ->
                val status = lease.client.checkFeatureStatus().await().toCapabilityStatus()
                AiCapability(
                    task = AiTask.IMAGE_DESCRIPTION,
                    status = status,
                    provider = AiProvider.GEMINI_NANO,
                    baseModelName = lease.client.baseModelNameOrNull(status),
                )
            }
        }

    /**
     * Advanced, GenAI-backed speech recognition.
     *
     * Requested explicitly as MODE_ADVANCED so the capability screen can tell it apart
     * from the basic recogniser, which is available on far more devices. This API is
     * still an alpha surface, so an unsupported answer here is expected on most hardware.
     */
    private suspend fun resolveAdvancedTranscription(): AiCapability = AiCapability(
        task = AiTask.ADVANCED_TRANSCRIPTION,
        status = transcriptionEngine.status(TranscriptionMode.ADVANCED),
        provider = AiProvider.GEMINI_NANO,
    )

    /**
     * Basic on-device speech recognition.
     *
     * Tries ML Kit's MODE_BASIC first. A device that cannot serve that may still have a
     * platform recogniser, so that is checked as a fallback rather than declaring speech
     * input dead - and the provider reported changes accordingly, so the capability
     * screen never misattributes which engine would actually run.
     */
    private suspend fun resolveBasicTranscription(): AiCapability {
        val status = transcriptionEngine.status(TranscriptionMode.BASIC)
        return AiCapability(
            task = AiTask.BASIC_TRANSCRIPTION,
            status = status,
            provider = transcriptionEngine.provider(TranscriptionMode.BASIC),
            detail = if (status == AiCapabilityStatus.UNSUPPORTED) {
                "No on-device speech recognition service"
            } else null,
        )
    }

    /**
     * Text recognition ships inside the APK, so it is available wherever the app runs.
     *
     * This is stated as a fact rather than probed because there is nothing to probe: the
     * bundled model has no download step and no Play services dependency at inference
     * time.
     */
    private fun resolveTextRecognition(): AiCapability = AiCapability(
        task = AiTask.TEXT_RECOGNITION,
        status = AiCapabilityStatus.AVAILABLE,
        provider = AiProvider.ML_KIT,
    )

    /**
     * On-device translation is available; individual language packs are downloaded
     * separately and reported by the Translate and Models screens.
     */
    private fun resolveTranslation(): AiCapability = AiCapability(
        task = AiTask.TRANSLATION,
        status = AiCapabilityStatus.AVAILABLE,
        provider = AiProvider.ML_KIT,
    )

    /**
     * Runs a GenAI probe, turning any throw into an honest status rather than a crash.
     *
     * A device with no AICore at all throws from the very first call, which must read as
     * "unsupported", not as an app failure.
     */
    private suspend inline fun genAi(
        task: AiTask,
        block: () -> AiCapability,
    ): AiCapability = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        val failure = e.toAiFailure()
        AiCapability(
            task = task,
            status = if (failure is AiFailure.Unsupported) {
                AiCapabilityStatus.UNSUPPORTED
            } else {
                AiCapabilityStatus.ERROR
            },
            provider = AiProvider.GEMINI_NANO,
            detail = failure.technicalDetail,
        )
    }

}

/**
 * Model name, but only when the feature is actually available.
 *
 * Asking an unavailable feature for its model name either fails or returns something
 * meaningless, and showing that would be worse than showing nothing.
 */
private suspend fun Summarizer.baseModelNameOrNull(
    status: AiCapabilityStatus,
): String? = if (status == AiCapabilityStatus.AVAILABLE) {
    optionalModelName { getBaseModelName().await() }
} else {
    null
}

private suspend fun Rewriter.baseModelNameOrNull(
    status: AiCapabilityStatus,
): String? = if (status == AiCapabilityStatus.AVAILABLE) {
    optionalModelName { getBaseModelName().await() }
} else {
    null
}

private suspend fun Proofreader.baseModelNameOrNull(
    status: AiCapabilityStatus,
): String? = if (status == AiCapabilityStatus.AVAILABLE) {
    optionalModelName { getBaseModelName().await() }
} else {
    null
}

private suspend fun ImageDescriber.baseModelNameOrNull(
    status: AiCapabilityStatus,
): String? = if (status == AiCapabilityStatus.AVAILABLE) {
    optionalModelName { getBaseModelName().await() }
} else {
    null
}

/** A model name is optional; cancellation of its operation is not an optional failure. */
private suspend fun optionalModelName(block: suspend () -> String): String? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
