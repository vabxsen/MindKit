package com.localai.toolkit.feature.settings

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.mlkit.TranslationEngine
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val engine = TestModels()

    @Test fun `retry after failed listing clears the error and shows downloaded languages`() = runTest {
        engine.failListing = true
        val vm = main.own(ModelsViewModel(engine))
        runCurrent()
        assertThat(vm.uiState.value.failure).isNotNull()
        engine.failListing = false
        vm.refresh()
        runCurrent()
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(vm.uiState.value.downloaded).containsExactly("en")
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test fun `download and delete update indicators and repeated taps cannot start competing work`() = runTest {
        val vm = main.own(ModelsViewModel(engine))
        runCurrent()
        engine.downloadGate = CompletableDeferred()
        vm.onDownload("es")
        vm.onDownload("fr")
        vm.onDelete("en")
        runCurrent()
        assertThat(engine.downloads).containsExactly("es")
        assertThat(vm.uiState.value.busyCode).isEqualTo("es")
        engine.downloadGate.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.downloaded).containsExactly("en", "es")
        vm.onDelete("es")
        runCurrent()
        assertThat(vm.uiState.value.downloaded).containsExactly("en")
        assertThat(vm.uiState.value.busyCode).isNull()
    }

    @Test fun `search filters languages and installed rows sort first`() = runTest {
        val vm = main.own(ModelsViewModel(engine))
        runCurrent()
        assertThat(vm.uiState.value.visibleLanguages.first().code).isEqualTo("en")
        vm.onQueryChange("SPAN")
        assertThat(vm.uiState.value.visibleLanguages.map { it.code }).containsExactly("es")
        vm.onQueryChange("nothing matches")
        assertThat(vm.uiState.value.visibleLanguages).isEmpty()
    }

    private class TestModels : TranslationEngine {
        var failListing = false
        var downloadGate = CompletableDeferred(Unit)
        val downloads = mutableListOf<String>()
        private val installed = mutableSetOf("en")
        override fun supportedLanguages() = listOf(
            TranslationLanguage("fr", "French"), TranslationLanguage("en", "English"), TranslationLanguage("es", "Spanish"),
        )
        override suspend fun downloadedLanguages(): Set<String> {
            if (failListing) throw AiException(AiFailure.Unknown())
            return installed.toSet()
        }
        override suspend fun downloadLanguage(code: String, requireWifi: Boolean) {
            downloads += code
            downloadGate.await()
            installed += code
        }
        override suspend fun deleteLanguage(code: String) { installed -= code }
        override suspend fun translate(text: String, source: String, target: String) = text
        override suspend fun detectLanguage(text: String): String? = null
        override fun close() = Unit
    }
}
