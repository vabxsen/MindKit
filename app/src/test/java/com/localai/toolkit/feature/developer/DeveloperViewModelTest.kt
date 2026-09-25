package com.localai.toolkit.feature.developer

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.fake.FakeAiEngine
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.AiStreamChunk
import com.localai.toolkit.ai.engine.AskRequest
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.usecase.devtools.DevTools
import com.localai.toolkit.testing.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperViewModelTest {
    @Test fun `both AI explainers retain rejected input and run again after editing`() = runTest {
        listOf(DeveloperTool.EXPLAIN_CODE, DeveloperTool.EXPLAIN_ERROR).forEach { tool ->
            var attempts = 0
            val rejected = AiFailure.InvalidInput(tooLarge = true)
            val engine = object : AiEngine by FakeAiEngine() {
                override fun generateText(request: AskRequest) = flow {
                    if (++attempts == 1) throw AiException(rejected)
                    assertThat(request.prompt).contains("Shorter input")
                    emit(AiStreamChunk("Recovered explanation", true))
                }
            }
            val vm = model(engine)
            vm.onInputChange("Original input")
            vm.run(tool)
            runCurrent()
            assertThat(vm.uiState.value.input).isEqualTo("Original input")
            assertThat(vm.uiState.value.isGenerating).isFalse()
            assertThat(vm.uiState.value.failure).isEqualTo(rejected)
            vm.onInputChange("Shorter input")
            vm.run(tool)
            runCurrent()
            assertThat(vm.uiState.value.failure).isNull()
            assertThat(vm.uiState.value.output).isEqualTo("Recovered explanation")
            assertThat(attempts).isEqualTo(2)
        }
    }

    @Test fun `deleting a saved result makes Save usable again`() = runTest {
        val history = MemoryHistory()
        val vm = model(history = history)
        vm.onInputChange("hello")
        vm.run(DeveloperTool.BASE64)
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        history.delete(history.items.value.single().id)
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        vm.onSave()
        runCurrent()
        assertThat(history.items.value.single().output).isEqualTo("aGVsbG8=")
        assertThat(vm.uiState.value.savedToHistory).isTrue()
    }

    @Test fun `clearing history makes the retained result saveable again`() = runTest {
        val history = MemoryHistory()
        val vm = model(history = history)
        vm.onInputChange("hello")
        vm.run(DeveloperTool.BASE64)
        vm.onSave()
        runCurrent()
        history.deleteAll()
        runCurrent()
        assertThat(vm.uiState.value.output).isEqualTo("aGVsbG8=")
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        vm.onSave()
        runCurrent()
        assertThat(history.items.value).hasSize(1)
    }

    @Test fun `oversized input is preserved with an actionable error and shorter input can run`() = runTest {
        val vm = model()
        val huge = "a".repeat(100_001)
        vm.onInputChange(huge)
        vm.run(DeveloperTool.BASE64)
        assertThat(vm.uiState.value.input).isEqualTo(huge)
        assertThat(vm.uiState.value.inputError).isEqualTo(DeveloperViewModel.INPUT_TOO_LARGE_MARKER)
        assertThat(vm.uiState.value.output).isEmpty()
        vm.onInputChange("ok")
        vm.run(DeveloperTool.BASE64)
        assertThat(vm.uiState.value.inputError).isNull()
        assertThat(vm.uiState.value.output).isEqualTo("b2s=")
    }
    @get:Rule val main = MainDispatcherRule()
    private fun model(engine: AiEngine = FakeAiEngine(), history: MemoryHistory = MemoryHistory()) = main.own(
        DeveloperViewModel(engine, history, TestCapabilities(), MemorySettings()),
    )

    @Test fun `saved JSON validation result is readable outside the developer screen`() = runTest {
        val history = MemoryHistory()
        val vm = model(history = history)
        vm.onInputChange("{}")
        vm.run(DeveloperTool.JSON_VALIDATOR)
        vm.onSave()
        runCurrent()
        assertThat(history.items.value.single().output).isEqualTo("Valid JSON\n\n{}")
    }

    @Test fun `new UUID and new hash results become saveable again`() = runTest {
        val vm = model()
        vm.onUuidCountChange(5)
        vm.run(DeveloperTool.UUID_GENERATOR)
        assertThat(vm.uiState.value.output.lines()).hasSize(5)
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        val previous = vm.uiState.value.output
        vm.run(DeveloperTool.UUID_GENERATOR)
        assertThat(vm.uiState.value.output).isNotEqualTo(previous)
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        vm.onInputChange("abc")
        vm.run(DeveloperTool.HASH_GENERATOR)
        vm.onSave()
        runCurrent()
        vm.onHashAlgorithmChange(DevTools.HashAlgorithm.MD5)
        assertThat(vm.uiState.value.output).isEqualTo("900150983cd24fb0d6963f7d28e17f72")
        assertThat(vm.uiState.value.savedToHistory).isFalse()
    }

    @Test fun `base64 and url direction actions run their selected operation`() = runTest {
        val vm = model()
        vm.onInputChange("hello world")
        vm.run(DeveloperTool.BASE64)
        assertThat(vm.uiState.value.output).isEqualTo("aGVsbG8gd29ybGQ=")
        vm.onBase64DirectionChange(true)
        assertThat(vm.uiState.value.output).isEmpty()
        vm.onInputChange("aGVsbG8gd29ybGQ=")
        vm.run(DeveloperTool.BASE64)
        assertThat(vm.uiState.value.output).isEqualTo("hello world")
        vm.onInputChange("a b")
        vm.run(DeveloperTool.URL_CODEC)
        assertThat(vm.uiState.value.output).isEqualTo("a%20b")
        vm.onUrlDirectionChange(true)
        vm.onInputChange("a%20b")
        vm.run(DeveloperTool.URL_CODEC)
        assertThat(vm.uiState.value.output).isEqualTo("a b")
    }

    @Test fun `editing active explanation cancels it and clears old output`() = runTest {
        val vm = model(FakeAiEngine(chunkDelayMillis = 100))
        vm.onInputChange("old code")
        vm.run(DeveloperTool.EXPLAIN_CODE)
        advanceTimeBy(101)
        runCurrent()
        assertThat(vm.uiState.value.isGenerating).isTrue()
        vm.onInputChange("new code")
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(vm.uiState.value.isGenerating).isFalse()
        assertThat(vm.uiState.value.output).isEmpty()
        vm.run(DeveloperTool.EXPLAIN_CODE)
        runCurrent()
        vm.onErrorLanguageChange(ErrorLanguage.PYTHON)
        assertThat(vm.uiState.value.isGenerating).isFalse()
    }

    @Test fun `json controls format validate and surface malformed input`() = runTest {
        val vm = model()
        vm.onInputChange("{\"a\":1}")
        vm.run(DeveloperTool.JSON_FORMATTER)
        assertThat(vm.uiState.value.output).isEqualTo("{\n  \"a\": 1\n}")
        vm.run(DeveloperTool.JSON_VALIDATOR)
        assertThat(vm.uiState.value.output).startsWith(DeveloperViewModel.VALID_JSON_MARKER)
        vm.onInputChange("{bad}")
        vm.run(DeveloperTool.JSON_VALIDATOR)
        assertThat(vm.uiState.value.inputError).isNotNull()
        assertThat(vm.uiState.value.output).isEmpty()
    }

    @Test fun `timestamp accepts ISO and Now replaces the input and result`() = runTest {
        val vm = model()
        vm.onInputChange("2026-09-20T00:00:00Z")
        vm.run(DeveloperTool.TIMESTAMP)
        assertThat(vm.uiState.value.output).contains("2026-09-20T00:00:00Z")
        vm.useCurrentTimestamp()
        val seconds = vm.uiState.value.input.toLong()
        assertThat(seconds).isAtLeast(System.currentTimeMillis() / 1_000 - 2)
        assertThat(vm.uiState.value.output).contains(seconds.toString())
        vm.onClear()
        assertThat(vm.uiState.value.input).isEmpty()
        assertThat(vm.uiState.value.output).isEmpty()
    }
}
