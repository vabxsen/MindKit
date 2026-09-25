package com.localai.toolkit.feature.translate

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.mlkit.TranslationEngine
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslateViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val engine = TestTranslator()
    private fun model(settings: SettingsRepository = MemorySettings(), history: MemoryHistory = MemoryHistory()) =
        main.own(TranslateViewModel(engine, history, settings, ToolHandoff(), main.settingsActions(settings, history)))

    @Test fun `clearing history makes the existing translation saveable again`() = runTest {
        val history = MemoryHistory()
        val vm = model(history = history)
        runCurrent()
        vm.onInputChange("Hello")
        vm.onTranslate()
        runCurrent()
        engine.result.complete("Hola")
        runCurrent()
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        history.deleteAll()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        assertThat(vm.uiState.value.output).isEqualTo("Hola")
        vm.onSave()
        runCurrent()
        assertThat(history.items.value.single().output).isEqualTo("Hola")
    }

    @Test fun `slow preference initialization cannot overwrite a user language choice`() = runTest {
        val stored = CompletableDeferred<AppSettings>()
        val settings = object : SettingsRepository by MemorySettings() {
            override val settings = flow { emit(stored.await()) }
        }
        val vm = model(settings)
        runCurrent()
        vm.onSourceChange("fr")
        stored.complete(AppSettings(lastTranslateSource = "en", lastTranslateTarget = "de"))
        runCurrent()
        assertThat(vm.uiState.value.sourceCode).isEqualTo("fr")
        assertThat(vm.uiState.value.targetCode).isEqualTo("es")
    }

    @Test fun `slow earlier preference write cannot become the final stored language pair`() = runTest {
        val memory = MemorySettings()
        val firstWrite = CompletableDeferred<Unit>()
        var calls = 0
        val settings = object : SettingsRepository by memory {
            override suspend fun setTranslateLanguages(source: String?, target: String?) {
                if (++calls == 1) firstWrite.await()
                memory.setTranslateLanguages(source, target)
            }
        }
        val vm = model(settings)
        runCurrent()
        vm.onSourceChange("fr")
        runCurrent()
        vm.onTargetChange("de")
        runCurrent()
        firstWrite.complete(Unit)
        runCurrent()
        assertThat(memory.settings.value.lastTranslateSource).isEqualTo("fr")
        assertThat(memory.settings.value.lastTranslateTarget).isEqualTo("de")
    }

    @Test fun `clear during translation cancels work and allows another translation`() = runTest {
        val vm = model()
        runCurrent()
        vm.onInputChange("Hello")
        vm.onTranslate()
        runCurrent()
        assertThat(vm.uiState.value.isTranslating).isTrue()
        vm.onClear()
        runCurrent()
        engine.result.complete("old result")
        runCurrent()
        assertThat(vm.uiState.value.output).isEmpty()
        assertThat(vm.uiState.value.isTranslating).isFalse()
        vm.onInputChange("Another message")
        assertThat(vm.uiState.value.canTranslate).isTrue()
    }

    @Test fun `starting translation accepts the displayed pair before preferences finish loading`() = runTest {
        val stored = CompletableDeferred<AppSettings>()
        val settings = object : SettingsRepository by MemorySettings() {
            override val settings = flow { emit(stored.await()) }
        }
        val vm = model(settings)
        runCurrent()
        vm.onInputChange("Hello")
        vm.onTranslate()
        runCurrent()
        stored.complete(AppSettings(lastTranslateSource = "fr", lastTranslateTarget = "de"))
        engine.result.complete("Hola")
        runCurrent()
        assertThat(vm.uiState.value.sourceCode).isEqualTo("en")
        assertThat(vm.uiState.value.targetCode).isEqualTo("es")
        assertThat(vm.uiState.value.output).isEqualTo("Hola")
    }

    @Test fun `failed preference write is retryable without losing selection or translating`() = runTest {
        val memory = MemorySettings()
        var fail = true
        var writes = 0
        val settings = object : SettingsRepository by memory {
            override suspend fun setTranslateLanguages(source: String?, target: String?) {
                writes++
                if (fail) throw IOException("storage unavailable")
                memory.setTranslateLanguages(source, target)
            }
        }
        val vm = model(settings)
        runCurrent()
        vm.onSourceChange("fr")
        runCurrent()
        assertThat(vm.uiState.value.languagePreferencesFailed).isTrue()
        assertThat(vm.uiState.value.sourceCode).isEqualTo("fr")
        fail = false
        vm.onRetryLanguagePreferences()
        vm.onRetryLanguagePreferences()
        runCurrent()
        assertThat(writes).isEqualTo(2)
        assertThat(memory.settings.value.lastTranslateSource).isEqualTo("fr")
        assertThat(vm.uiState.value.languagePreferencesFailed).isFalse()
        assertThat(vm.uiState.value.isTranslating).isFalse()
    }

    @Test fun `an older failed write cannot mark a newer successful selection failed`() = runTest {
        val memory = MemorySettings()
        val firstWrite = CompletableDeferred<Unit>()
        var writes = 0
        val settings = object : SettingsRepository by memory {
            override suspend fun setTranslateLanguages(source: String?, target: String?) {
                if (++writes == 1) {
                    firstWrite.await()
                    throw IOException("old write failed")
                }
                memory.setTranslateLanguages(source, target)
            }
        }
        val vm = model(settings)
        runCurrent()
        vm.onSourceChange("fr")
        runCurrent()
        vm.onTargetChange("de")
        runCurrent()
        firstWrite.complete(Unit)
        runCurrent()
        assertThat(memory.settings.value.lastTranslateTarget).isEqualTo("de")
        assertThat(vm.uiState.value.languagePreferencesFailed).isFalse()
    }

    @Test fun `preference read failure can retry without blocking model checks`() = runTest {
        var fail = true
        val settings = object : SettingsRepository by MemorySettings() {
            override val settings = flow {
                if (fail) throw IOException("read failed")
                emit(AppSettings(lastTranslateSource = "fr", lastTranslateTarget = "de"))
            }
        }
        val vm = model(settings)
        runCurrent()
        assertThat(vm.uiState.value.languagePreferencesFailed).isTrue()
        assertThat(vm.uiState.value.downloadedLanguages).isNotEmpty()
        fail = false
        vm.onRetryLanguagePreferences()
        runCurrent()
        assertThat(vm.uiState.value.languagePreferencesFailed).isFalse()
        assertThat(vm.uiState.value.sourceCode).isEqualTo("fr")
        assertThat(vm.uiState.value.targetCode).isEqualTo("de")
    }

    @Test fun `recovery fallback waits for real preferences and handoff cannot overwrite later typing`() = runTest {
        val values = MutableStateFlow(AppSettings(storageReadFailed = true))
        val settings = object : SettingsRepository by MemorySettings() {
            override val settings = values
        }
        val handoff = ToolHandoff().apply { send(HandoffPayload(ToolId.TRANSLATE, text = "Shared text")) }
        val vm = main.own(TranslateViewModel(engine, MemoryHistory(), settings, handoff, main.settingsActions(settings)))
        assertThat(vm.uiState.value.input).isEqualTo("Shared text")
        vm.onInputChange("Edited")
        runCurrent()
        values.value = AppSettings(lastTranslateSource = "fr", lastTranslateTarget = "de")
        runCurrent()
        assertThat(vm.uiState.value.input).isEqualTo("Edited")
        assertThat(vm.uiState.value.sourceCode).isEqualTo("fr")
        assertThat(handoff.consume(ToolId.TRANSLATE)).isNull()
    }

    @Test fun `changing language cancels old request and prevents a mismatched result`() = runTest {
        val vm = model()
        runCurrent()
        vm.onInputChange("Hello")
        vm.onTranslate()
        runCurrent()
        vm.onTargetChange("fr")
        engine.result.complete("Hola")
        runCurrent()
        assertThat(vm.uiState.value.targetCode).isEqualTo("fr")
        assertThat(vm.uiState.value.output).isEmpty()
        assertThat(vm.uiState.value.canTranslate).isTrue()
    }

    @Test fun `swap uses last result as new input and clears saved state`() = runTest {
        val vm = model()
        runCurrent()
        vm.onInputChange("Hello")
        engine.result.complete("Hola")
        vm.onTranslate()
        runCurrent()
        vm.onSave()
        runCurrent()
        vm.onSwapLanguages()
        assertThat(vm.uiState.value.input).isEqualTo("Hola")
        assertThat(vm.uiState.value.sourceCode).isEqualTo("es")
        assertThat(vm.uiState.value.output).isEmpty()
        assertThat(vm.uiState.value.savedToHistory).isFalse()
    }

    @Test fun `clear cancels language guess so the suggestion cannot return`() = runTest {
        val vm = model()
        runCurrent()
        vm.onInputChange("Bonjour tout le monde")
        runCurrent()
        vm.onClear()
        engine.detected.complete("fr")
        runCurrent()
        assertThat(vm.uiState.value.detectedSourceCode).isNull()
    }

    @Test fun `model checks preserve edits made while suspended and coalesce repeated refresh`() = runTest {
        engine.modelResult = CompletableDeferred()
        val vm = model()
        runCurrent()
        vm.onInputChange("Keep my draft")
        vm.onTargetChange("fr")
        vm.onRefreshModels()
        vm.onRefreshModels()
        assertThat(engine.modelChecks).isEqualTo(1)
        engine.modelResult!!.complete(setOf("en", "fr"))
        runCurrent()
        assertThat(vm.uiState.value.input).isEqualTo("Keep my draft")
        assertThat(vm.uiState.value.targetCode).isEqualTo("fr")
        assertThat(vm.uiState.value.downloadedLanguages).containsExactly("en", "fr")
        assertThat(vm.uiState.value.isRefreshingModels).isFalse()
    }

    @Test fun `model check failure has its own retry and later refresh observes deleted packs`() = runTest {
        engine.modelError = AiException(AiFailure.Unknown("model list unavailable"))
        val vm = model()
        runCurrent()
        assertThat(vm.uiState.value.modelFailure).isNotNull()
        assertThat(vm.uiState.value.isRefreshingModels).isFalse()
        engine.modelError = null
        vm.onRefreshModels()
        runCurrent()
        assertThat(vm.uiState.value.modelFailure).isNull()
        assertThat(vm.uiState.value.missingModels).isEmpty()
        engine.models = setOf("en")
        vm.onRefreshModels()
        runCurrent()
        assertThat(vm.uiState.value.missingModels).containsExactly("es")
    }

    @Test fun `missing model inference failure refreshes packs to reveal download action`() = runTest {
        val vm = model()
        runCurrent()
        engine.models = setOf("en")
        engine.result.completeExceptionally(AiException(AiFailure.ModelNotDownloaded()))
        vm.onInputChange("Hello")
        vm.onTranslate()
        runCurrent()
        assertThat(vm.uiState.value.isTranslating).isFalse()
        assertThat(vm.uiState.value.missingModels).containsExactly("es")
        assertThat(engine.modelChecks).isEqualTo(2)
    }

    @Test fun `noncancellable translation and detection cannot restore cleared state`() = runTest {
        engine.ignoreCancellation = true
        val vm = model()
        runCurrent()
        vm.onInputChange("Bonjour tout le monde")
        vm.onTranslate()
        runCurrent()
        vm.onClear()
        engine.result.complete("late translation")
        engine.detected.complete("fr")
        runCurrent()
        assertThat(vm.uiState.value.input).isEmpty()
        assertThat(vm.uiState.value.output).isEmpty()
        assertThat(vm.uiState.value.detectedSourceCode).isNull()
        assertThat(vm.uiState.value.isTranslating).isFalse()
    }

    @Test fun `unexpected translation and download exceptions leave retryable state`() = runTest {
        val vm = model()
        runCurrent()
        engine.result.completeExceptionally(IllegalStateException("unexpected vendor error"))
        vm.onInputChange("Hello")
        vm.onTranslate()
        runCurrent()
        assertThat(vm.uiState.value.failure).isInstanceOf(AiFailure.Unknown::class.java)
        assertThat(vm.uiState.value.isTranslating).isFalse()
        engine.downloadError = IllegalStateException("download unavailable")
        vm.onDownloadLanguage("fr")
        runCurrent()
        assertThat(vm.uiState.value.downloadingCode).isNull()
        assertThat(vm.uiState.value.failure).isNotNull()
        engine.downloadError = null
        vm.onRetryFailure()
        runCurrent()
        assertThat(engine.downloads).containsExactly("fr", "fr")
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(vm.uiState.value.downloadingCode).isNull()
    }

    private class TestTranslator : TranslationEngine {
        val result = CompletableDeferred<String>()
        val detected = CompletableDeferred<String?>()
        var modelResult: CompletableDeferred<Set<String>>? = null
        var modelError: Exception? = null
        var downloadError: Exception? = null
        var models = setOf("en", "es", "fr")
        var modelChecks = 0
        var ignoreCancellation = false
        val downloads = mutableListOf<String>()
        override fun supportedLanguages() = listOf(TranslationLanguage("en", "English"), TranslationLanguage("es", "Spanish"))
        override suspend fun downloadedLanguages(): Set<String> {
            modelChecks++
            modelError?.let { throw it }
            return modelResult?.await() ?: models
        }
        override suspend fun downloadLanguage(code: String, requireWifi: Boolean) {
            downloads += code
            downloadError?.let { throw it }
        }
        override suspend fun deleteLanguage(code: String) = Unit
        override suspend fun translate(text: String, source: String, target: String) =
            if (ignoreCancellation) withContext(NonCancellable) { result.await() } else result.await()
        override suspend fun detectLanguage(text: String) =
            if (ignoreCancellation) withContext(NonCancellable) { detected.await() } else detected.await()
        override fun close() = Unit
    }
}
