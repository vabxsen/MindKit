package com.localai.toolkit.ai.fake

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.AskRequest
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.engine.RewriteRequest
import com.localai.toolkit.ai.engine.RewriteStyle
import com.localai.toolkit.ai.engine.SummarizeRequest
import com.localai.toolkit.ai.engine.SummaryLength
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiTask
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Contract tests for the substituted engine.
 *
 * Because every ViewModel test relies on [FakeAiEngine] behaving like the real one, the
 * fake's own contract - cumulative streaming, failure propagation, resource release - is
 * verified here rather than assumed.
 */
class FakeAiEngineTest {

    @Test
    fun `generateText emits cumulative text and marks the last chunk final`() = runTest {
        val engine = FakeAiEngine()

        val chunks = engine.generateText(AskRequest(prompt = "hello there")).toList()

        assertThat(chunks).isNotEmpty()
        // Each emission must contain the previous one; collectors render emissions
        // directly rather than accumulating.
        chunks.zipWithNext().forEach { (previous, next) ->
            assertThat(next.text).startsWith(previous.text)
        }
        assertThat(chunks.dropLast(1).none { it.isFinal }).isTrue()
        assertThat(chunks.last().isFinal).isTrue()
        assertThat(chunks.last().text).contains("hello there")
    }

    @Test
    fun `summary length maps to the expected number of bullets`() = runTest {
        val engine = FakeAiEngine()

        val short = engine.summarize(SummarizeRequest("text", SummaryLength.SHORT))
        val medium = engine.summarize(SummarizeRequest("text", SummaryLength.MEDIUM))
        val detailed = engine.summarize(SummarizeRequest("text", SummaryLength.DETAILED))

        assertThat(short.lines()).hasSize(1)
        assertThat(medium.lines()).hasSize(2)
        assertThat(detailed.lines()).hasSize(3)
    }

    @Test
    fun `configured failure is thrown as an AiException carrying the failure`() = runTest {
        val engine = FakeAiEngine(failWith = AiFailure.Busy(retryAfterMillis = 500))

        val thrown = runCatching {
            engine.rewrite(RewriteRequest("text", RewriteStyle.PROFESSIONAL))
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(AiException::class.java)
        assertThat((thrown as AiException).failure).isInstanceOf(AiFailure.Busy::class.java)
    }

    @Test
    fun `downloadModel reports progress and completes`() = runTest {
        val engine = FakeAiEngine(downloadSteps = 4)

        engine.downloadModel(AiTask.SUMMARIZE).test {
            assertThat(awaitItem()).isInstanceOf(ModelDownloadState.Started::class.java)
            repeat(4) {
                val item = awaitItem()
                assertThat(item).isInstanceOf(ModelDownloadState.InProgress::class.java)
                assertThat((item as ModelDownloadState.InProgress).fraction).isNotNull()
            }
            assertThat(awaitItem()).isEqualTo(ModelDownloadState.Completed)
            awaitComplete()
        }
    }

    @Test
    fun `downloadModel reports failure instead of throwing`() = runTest {
        // A download failure is a state the UI renders, not an exception the screen has
        // to catch.
        val engine = FakeAiEngine(failWith = AiFailure.DownloadFailed())

        val states = engine.downloadModel(AiTask.ASK).toList()

        assertThat(states).hasSize(1)
        assertThat(states.single()).isInstanceOf(ModelDownloadState.Failed::class.java)
    }

    @Test
    fun `release with no task releases every task`() {
        val engine = FakeAiEngine()

        engine.release(null)

        assertThat(engine.releasedTasks).containsExactlyElementsIn(AiTask.entries)
    }

    @Test
    fun `release with a task releases only that task`() {
        val engine = FakeAiEngine()

        engine.release(AiTask.ASK)

        assertThat(engine.releasedTasks).containsExactly(AiTask.ASK)
    }
}
