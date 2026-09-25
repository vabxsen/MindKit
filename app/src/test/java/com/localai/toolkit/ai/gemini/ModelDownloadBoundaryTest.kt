package com.localai.toolkit.ai.gemini

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadBoundaryTest {
    @Test fun `SDK setup is deferred until collection and repeated per collection`() = runTest {
        var starts = 0
        val download = mappedModelDownload { starts++; flowOf(ModelDownloadState.Completed) }
        assertThat(starts).isEqualTo(0)
        assertThat(download.toList()).containsExactly(ModelDownloadState.Completed)
        download.toList()
        assertThat(starts).isEqualTo(2)
    }

    @Test fun `synchronous SDK setup failure retains a typed app failure`() = runTest {
        val states = mappedModelDownload { throw AiException(AiFailure.NotEnoughStorage()) }.toList()
        assertThat((states.single() as ModelDownloadState.Failed).reason).isInstanceOf(AiFailure.NotEnoughStorage::class.java)
    }

    @Test fun `cancellation closes the producer and is not converted to a failed download`() = runTest {
        var closed = false
        val states = mutableListOf<ModelDownloadState>()
        val work = async {
            mappedModelDownload { flow {
                try { emit(ModelDownloadState.Started(null)); awaitCancellation() }
                finally { closed = true }
            } }.toList(states)
        }
        runCurrent()
        work.cancel()
        runCurrent()
        assertThat(work.isCancelled).isTrue()
        assertThat(closed).isTrue()
        assertThat(states).containsExactly(ModelDownloadState.Started(null))
    }

    @Test fun `consumer exception is not misreported as an SDK download failure`() = runTest {
        val expected = IllegalArgumentException("consumer failed")
        val actual = runCatching {
            mappedModelDownload { flowOf(ModelDownloadState.Completed) }.collect { throw expected }
        }.exceptionOrNull()
        assertThat(actual).isSameInstanceAs(expected)
    }
}
