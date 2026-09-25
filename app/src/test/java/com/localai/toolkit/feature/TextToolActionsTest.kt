package com.localai.toolkit.feature

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.*
import com.localai.toolkit.ai.fake.FakeAiEngine
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.feature.common.HistorySaveFeedback
import com.localai.toolkit.feature.proofread.ProofreadViewModel
import com.localai.toolkit.feature.rewrite.RewriteViewModel
import com.localai.toolkit.feature.summarize.SummarizeViewModel
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextToolActionsTest {
    @get:Rule val main = MainDispatcherRule()
    private val history = MemoryHistory()
    private val handoff = ToolHandoff()
    private fun summary(engine: AiEngine = FakeAiEngine(), repository: HistoryRepository = history) = main.own(
        SummarizeViewModel(engine, repository, handoff, TestCapabilities(), MemorySettings()),
    )
    private fun rewrite(engine: AiEngine = FakeAiEngine()) = main.own(
        RewriteViewModel(engine, history, handoff, TestCapabilities(), MemorySettings()),
    )
    private fun proofread(engine: AiEngine = FakeAiEngine()) = main.own(
        ProofreadViewModel(engine, history, handoff, TestCapabilities(), MemorySettings()),
    )

    @Test fun `clearing history re-enables Save for summary rewrite and proofread results`() = runTest {
        val summary = summary()
        val rewrite = rewrite()
        val proofread = proofread()
        summary.onInputChange("An article")
        summary.onSummarize()
        rewrite.onInputChange("Original text")
        rewrite.onRewrite()
        proofread.onInputChange("hello   world")
        proofread.onProofread()
        runCurrent()
        summary.onSave()
        rewrite.onSave()
        proofread.onSave()
        runCurrent()
        assertThat(history.items.value).hasSize(3)
        assertThat(summary.uiState.value.savedToHistory).isTrue()
        assertThat(rewrite.uiState.value.savedToHistory).isTrue()
        assertThat(proofread.uiState.value.savedToHistory).isTrue()
        history.deleteAll()
        runCurrent()
        assertThat(summary.uiState.value.savedToHistory).isFalse()
        assertThat(rewrite.uiState.value.savedToHistory).isFalse()
        assertThat(proofread.uiState.value.savedToHistory).isFalse()
        summary.onSave()
        rewrite.onSave()
        proofread.onSave()
        runCurrent()
        assertThat(history.items.value).hasSize(3)
    }

    @Test fun `summary save uses the summarized source not later edits and saves once`() = runTest {
        val vm = summary()
        vm.onInputChange("Original article")
        vm.onSummarize()
        runCurrent()
        vm.onInputChange("A different article")
        vm.onSave()
        vm.onSave()
        runCurrent()
        vm.onSave()
        runCurrent()
        assertThat(history.items.value).hasSize(1)
        assertThat(history.items.value.single().inputPreview).isEqualTo("Original article")
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        assertThat(vm.saveFeedback.first()).isEqualTo(HistorySaveFeedback.SAVED)
    }

    @Test fun `clear during save does not mark the cleared screen saved`() = runTest {
        val pending = CompletableDeferred<Unit>()
        val repository = object : HistoryRepository by history {
            override suspend fun save(item: HistoryItem): Long {
                pending.await()
                return history.save(item)
            }
        }
        val vm = summary(repository = repository)
        vm.onInputChange("Article")
        vm.onSummarize()
        runCurrent()
        vm.onSave()
        runCurrent()
        vm.onClear()
        pending.complete(Unit)
        runCurrent()
        assertThat(history.items.value).hasSize(1)
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        assertThat(vm.uiState.value.hasResult).isFalse()
    }

    @Test fun `changing summary options cancels the obsolete request`() = runTest {
        val pending = CompletableDeferred<String>()
        val engine = object : AiEngine by FakeAiEngine() {
            override suspend fun summarize(request: SummarizeRequest) = pending.await()
        }
        val vm = summary(engine)
        vm.onInputChange("Article")
        vm.onSummarize()
        runCurrent()
        vm.onLengthChange(SummaryLength.SHORT)
        pending.complete("old medium summary")
        runCurrent()
        assertThat(vm.uiState.value.summary).isEmpty()
        assertThat(vm.uiState.value.canSummarize).isTrue()
    }

    @Test fun `rewrite compare accept and save retain original request metadata`() = runTest {
        val vm = rewrite()
        vm.onInputChange("Original text")
        vm.onStyleChange(RewriteStyle.FRIENDLY)
        vm.onRewrite()
        runCurrent()
        val result = vm.uiState.value.rewritten
        vm.onToggleComparison()
        assertThat(vm.uiState.value.showOriginal).isTrue()
        vm.onAccept()
        assertThat(vm.uiState.value.input).isEqualTo(result)
        assertThat(vm.uiState.value.comparedOriginal).isEqualTo("Original text")
        vm.onStyleChange(RewriteStyle.PROFESSIONAL)
        vm.onSave()
        runCurrent()
        assertThat(history.items.value.single().metadata).isEqualTo(RewriteStyle.FRIENDLY.name)
        vm.sendTo(ToolId.TRANSLATE)
        assertThat(handoff.consume(ToolId.TRANSLATE)?.text).isEqualTo(result)
    }

    @Test fun `editing rewrite during work cancels stale results`() = runTest {
        val pending = CompletableDeferred<String>()
        val engine = object : AiEngine by FakeAiEngine() {
            override suspend fun rewrite(request: RewriteRequest) = pending.await()
        }
        val vm = rewrite(engine)
        vm.onInputChange("First")
        vm.onRewrite()
        runCurrent()
        vm.onInputChange("Second")
        pending.complete("Old answer")
        runCurrent()
        assertThat(vm.uiState.value.rewritten).isEmpty()
        assertThat(vm.uiState.value.canRewrite).isTrue()
    }

    @Test fun `proofreading unchanged text cannot reappear as a correction after editing`() = runTest {
        val vm = proofread()
        vm.onInputChange("Correct text")
        vm.onProofread()
        runCurrent()
        assertThat(vm.uiState.value.noChangesSuggested).isTrue()
        vm.onInputChange("different text")
        assertThat(vm.uiState.value.hasResult).isFalse()
        assertThat(vm.uiState.value.noChangesSuggested).isFalse()
    }

    @Test fun `proofread apply save and handoff retain the corrected pair`() = runTest {
        val vm = proofread()
        vm.onInputChange("hello   world")
        vm.onProofread()
        runCurrent()
        vm.onApply()
        assertThat(vm.uiState.value.input).isEqualTo("Hello world")
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.comparedOriginal).isEqualTo("hello   world")
        assertThat(history.items.value.single().inputPreview).isEqualTo("hello world")
        assertThat(history.items.value.single().output).isEqualTo("Hello world")
        vm.sendTo(ToolId.REWRITE)
        assertThat(handoff.consume(ToolId.REWRITE)?.text).isEqualTo("Hello world")
    }
}
