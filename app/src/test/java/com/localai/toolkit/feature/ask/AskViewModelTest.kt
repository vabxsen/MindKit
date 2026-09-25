package com.localai.toolkit.feature.ask

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.*
import com.localai.toolkit.ai.fake.FakeAiEngine
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.testing.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {
    @Test fun `rejected setup retains the question and retry preserves the next draft`() = runTest {
        var attempts = 0
        val rejected = AiFailure.InvalidInput(tooLarge = true)
        val engine = object : AiEngine by FakeAiEngine() {
            override fun generateText(request: AskRequest) = flow {
                if (++attempts == 1) throw AiException(rejected)
                emit(AiStreamChunk("Recovered answer", true))
            }
        }
        val vm = model(engine)
        vm.onInputChange("Original question")
        vm.onSend()
        runCurrent()
        val failedAnswer = vm.uiState.value.messages.last()
        assertThat(failedAnswer.failure).isEqualTo(rejected)
        assertThat(failedAnswer.isStreaming).isFalse()
        assertThat(vm.uiState.value.isGenerating).isFalse()
        assertThat(vm.uiState.value.messages.first().text).isEqualTo("Original question")
        vm.onInputChange("Next draft")
        vm.onRetry(failedAnswer.id)
        runCurrent()
        assertThat(attempts).isEqualTo(2)
        assertThat(vm.uiState.value.messages.last().failure).isNull()
        assertThat(vm.uiState.value.messages.last().text).isEqualTo("Recovered answer")
        assertThat(vm.uiState.value.input).isEqualTo("Next draft")
    }

    @Test fun `separate identical answers each honor their Save tap`() = runTest {
        val history = MemoryHistory()
        val engine = object : AiEngine by FakeAiEngine() {
            override fun generateText(request: AskRequest) = flow {
                emit(AiStreamChunk("Same answer", true))
            }
        }
        val vm = model(engine, history)
        val ids = mutableListOf<Long>()
        repeat(2) {
            vm.onInputChange("Same question")
            vm.onSend()
            runCurrent()
            ids += vm.uiState.value.messages.last().id
        }
        ids.forEach(vm::onSave)
        runCurrent()
        assertThat(vm.uiState.value.savedMessageIds).containsExactlyElementsIn(ids)
        assertThat(history.items.value).hasSize(2)
        history.delete(history.items.value.first().id)
        runCurrent()
        assertThat(vm.uiState.value.savedMessageIds).containsExactly(ids.last())
    }

    @get:Rule val main = MainDispatcherRule()
    private fun model(engine: AiEngine, history: MemoryHistory = MemoryHistory()) = main.own(
        AskViewModel(engine, history, ToolHandoff(), TestCapabilities(), MemorySettings()),
    )

    @Test fun `deleting one saved answer enables only its Save and clearing enables all`() = runTest {
        val history = MemoryHistory()
        val engine = object : AiEngine by FakeAiEngine() {
            override fun generateText(request: AskRequest) = flow {
                emit(AiStreamChunk("Answer to ${request.prompt}", true))
            }
        }
        val vm = model(engine, history)
        vm.onInputChange("first")
        vm.onSend()
        runCurrent()
        val first = vm.uiState.value.messages.last().id
        vm.onSave(first)
        runCurrent()
        val firstRow = history.items.value.single().id
        vm.onInputChange("second")
        vm.onSend()
        runCurrent()
        val second = vm.uiState.value.messages.last().id
        vm.onSave(second)
        runCurrent()
        assertThat(vm.uiState.value.savedMessageIds).containsExactly(first, second)
        history.delete(firstRow)
        runCurrent()
        assertThat(vm.uiState.value.savedMessageIds).containsExactly(second)
        vm.onSave(first)
        runCurrent()
        assertThat(history.items.value).hasSize(2)
        history.deleteAll()
        runCurrent()
        assertThat(vm.uiState.value.savedMessageIds).isEmpty()
    }

    @Test fun `stopping then immediately sending cannot clear the new generation busy flag`() = runTest {
        val engine = object : AiEngine by FakeAiEngine() {
            override fun generateText(request: AskRequest) = flow {
                emit(AiStreamChunk(request.prompt, false))
                awaitCancellation()
            }
        }
        val vm = model(engine)
        vm.onInputChange("first")
        vm.onSend()
        runCurrent()
        vm.onStop()
        vm.onInputChange("second")
        vm.onSend()
        runCurrent()
        assertThat(vm.uiState.value.isGenerating).isTrue()
        assertThat(vm.uiState.value.messages.last().text).isEqualTo("second")
    }

    @Test fun `new conversation cancels old work without resetting the new turn`() = runTest {
        val vm = model(FakeAiEngine(chunkDelayMillis = 100))
        vm.onInputChange("old conversation")
        vm.onSend()
        runCurrent()
        vm.onNewConversation()
        vm.onInputChange("new conversation")
        vm.onSend()
        runCurrent()
        assertThat(vm.uiState.value.messages).hasSize(2)
        assertThat(vm.uiState.value.isGenerating).isTrue()
    }

    @Test fun `retry targets the selected answer and preserves later turns and the draft`() = runTest {
        val requests = mutableListOf<AskRequest>()
        val engine = object : AiEngine by FakeAiEngine() {
            override fun generateText(request: AskRequest) = flow {
                requests += request
                emit(AiStreamChunk("Answer ${requests.size} to ${request.prompt}", true))
            }
        }
        val vm = model(engine)
        vm.onInputChange("first question")
        vm.onSend()
        runCurrent()
        val firstAnswer = vm.uiState.value.messages.last().id
        vm.onSave(firstAnswer)
        runCurrent()
        vm.onInputChange("second question")
        vm.onSend()
        runCurrent()
        val secondTurn = vm.uiState.value.messages.takeLast(2)
        vm.onInputChange("unsent draft")
        vm.onRetry(firstAnswer)
        runCurrent()
        assertThat(requests.last().prompt).isEqualTo("first question")
        assertThat(requests.last().history).isEmpty()
        assertThat(vm.uiState.value.messages[1].text).isEqualTo("Answer 3 to first question")
        assertThat(vm.uiState.value.messages.takeLast(2)).isEqualTo(secondTurn)
        assertThat(vm.uiState.value.input).isEqualTo("unsent draft")
        assertThat(vm.uiState.value.savedMessageIds).doesNotContain(firstAnswer)
    }

    @Test fun `retry during a generation cannot discard or restart messages`() = runTest {
        val vm = model(FakeAiEngine(chunkDelayMillis = 100))
        vm.onInputChange("question")
        vm.onSend()
        runCurrent()
        val before = vm.uiState.value
        vm.onRetry(before.messages.last().id)
        assertThat(vm.uiState.value).isEqualTo(before)
    }
}
