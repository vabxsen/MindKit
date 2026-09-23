package com.localai.toolkit.ai.mlkit

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** A language offered by on-device translation. */
data class TranslationLanguage(
    /** ML Kit language code, e.g. "en". */
    val code: String,
    /** Localised name for display, e.g. "English". */
    val displayName: String,
)

/** On-device translation plus the model management the UI needs to be honest about it. */
interface TranslationEngine {

    /** Every language ML Kit can translate, sorted by display name. */
    fun supportedLanguages(): List<TranslationLanguage>

    /** Language codes whose models are present on this device right now. */
    suspend fun downloadedLanguages(): Set<String>

    /**
     * Downloads the model for [code].
     *
     * @param requireWifi when true the download waits for an unmetered network.
     */
    suspend fun downloadLanguage(code: String, requireWifi: Boolean = false)

    /** Deletes a previously downloaded language model. */
    suspend fun deleteLanguage(code: String)

    /**
     * Translates [text] from [source] to [target].
     *
     * Fails with [com.localai.toolkit.domain.model.AiFailure.ModelNotDownloaded] rather
     * than downloading silently, so the user is always the one who decides to use the
     * network.
     */
    suspend fun translate(text: String, source: String, target: String): String

    /**
     * Best guess at the language of [text], as an ML Kit language code.
     *
     * Returns null when identification is inconclusive or the detected language is not
     * one that can be translated, so the caller can leave the picker alone rather than
     * assert something wrong.
     */
    suspend fun detectLanguage(text: String): String?

    /** Releases the cached translator and identifier. */
    fun close()
}

@Singleton
class MlKitTranslationEngine @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TranslationEngine {

    private val modelManager = RemoteModelManager.getInstance()

    // ML Kit creates one Translator per language pair. Keeping every pair alive would
    // hold native memory for models the user has moved on from, so exactly one is cached
    // and swapping pairs closes the previous one.
    private val translatorLock = Mutex()
    private var cachedPair: Pair<String, String>? = null
    private var cachedTranslator: Translator? = null

    private val languageIdentifier: LanguageIdentifier by lazy {
        LanguageIdentification.getClient()
    }

    override fun supportedLanguages(): List<TranslationLanguage> =
        TranslateLanguage.getAllLanguages()
            .map { code -> TranslationLanguage(code, displayNameOf(code)) }
            .sortedBy { it.displayName.lowercase(Locale.getDefault()) }

    override suspend fun downloadedLanguages(): Set<String> = withContext(ioDispatcher) {
        try {
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .map { it.language }
                .toSet()
        } catch (e: Exception) {
            throw AiException(e.toAiFailure(), e)
        }
    }

    override suspend fun downloadLanguage(code: String, requireWifi: Boolean) =
        withContext(ioDispatcher) {
            try {
                val conditions = DownloadConditions.Builder()
                    .apply { if (requireWifi) requireWifi() }
                    .build()
                modelManager.download(remoteModel(code), conditions).await()
                Unit
            } catch (e: Exception) {
                throw AiException(e.toAiFailure(), e)
            }
        }

    override suspend fun deleteLanguage(code: String) = withContext(ioDispatcher) {
        try {
            modelManager.deleteDownloadedModel(remoteModel(code)).await()
            // The cached translator may be holding the model that was just removed.
            translatorLock.withLock {
                if (cachedPair?.first == code || cachedPair?.second == code) {
                    closeCachedTranslator()
                }
            }
            Unit
        } catch (e: Exception) {
            throw AiException(e.toAiFailure(), e)
        }
    }

    override suspend fun translate(text: String, source: String, target: String): String =
        withContext(ioDispatcher) {
            try {
                translatorFor(source, target).translate(text).await()
            } catch (e: Exception) {
                throw AiException(e.toAiFailure(), e)
            }
        }

    override suspend fun detectLanguage(text: String): String? = withContext(ioDispatcher) {
        if (text.isBlank()) return@withContext null
        val tag = try {
            languageIdentifier.identifyLanguage(text).await()
        } catch (e: Exception) {
            // A failed guess is not worth surfacing as an error; the user can pick a
            // language themselves.
            return@withContext null
        }
        if (tag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return@withContext null
        // Not every identifiable language is translatable; fromLanguageTag returns null
        // for those, which is exactly the "don't guess" answer we want.
        TranslateLanguage.fromLanguageTag(tag)
    }

    override fun close() {
        cachedTranslator?.close()
        cachedTranslator = null
        cachedPair = null
        languageIdentifier.close()
    }

    private suspend fun translatorFor(source: String, target: String): Translator =
        translatorLock.withLock {
            val pair = source to target
            cachedTranslator?.takeIf { cachedPair == pair }?.let { return@withLock it }

            closeCachedTranslator()
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(options).also {
                cachedTranslator = it
                cachedPair = pair
            }
        }

    private fun closeCachedTranslator() {
        cachedTranslator?.close()
        cachedTranslator = null
        cachedPair = null
    }

    private fun remoteModel(code: String) = TranslateRemoteModel.Builder(code).build()

    private fun displayNameOf(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        // getDisplayLanguage echoes the code back when it has no name for it.
        return if (name.isBlank() || name == code) code.uppercase(Locale.ROOT) else name
    }
}
