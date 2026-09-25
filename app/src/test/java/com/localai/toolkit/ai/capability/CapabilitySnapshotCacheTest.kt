package com.localai.toolkit.ai.capability

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitySnapshotCacheTest {
    @Test fun `checking one task cannot skip the first full snapshot`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.cache.refresh(AiTask.ASK)
        fixture.calls.clear()
        fixture.cache.refresh(force = false)
        assertThat(fixture.calls).containsExactlyElementsIn(AiTask.entries).inOrder()
        assertThat(fixture.cache.snapshot.value.capabilities.keys).containsExactlyElementsIn(AiTask.entries)
    }

    @Test fun `a task refresh cannot renew expired results for every other task`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.cache.refresh(force = false)
        fixture.advance(60_001)
        fixture.cache.refresh(AiTask.ASK)
        fixture.calls.clear()
        fixture.cache.refresh(force = false)
        assertThat(fixture.calls).containsExactlyElementsIn(AiTask.entries).inOrder()
    }

    @Test fun `wall-clock rewind cannot keep an expired snapshot fresh`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.cache.refresh(force = false)
        fixture.calls.clear()
        fixture.elapsedMillis += 60_001
        fixture.wallMillis -= 3_600_000
        fixture.cache.refresh(force = false)
        assertThat(fixture.calls).containsExactlyElementsIn(AiTask.entries).inOrder()
        assertThat(fixture.cache.snapshot.value.lastCheckedAtEpochMillis).isEqualTo(fixture.wallMillis)
    }

    @Test fun `wall-clock adjustment does not expire a recently completed full check`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.cache.refresh(force = false)
        fixture.calls.clear()
        fixture.wallMillis += 3_600_000
        fixture.cache.refresh(force = false)
        assertThat(fixture.calls).isEmpty()
    }

    @Test fun `older task result cannot overwrite a later full refresh`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val releaseOld = CompletableDeferred<Unit>()
        var asks = 0
        fixture.resolve = { task ->
            if (task == AiTask.ASK && ++asks == 1) {
                releaseOld.await()
                capability(task, AiCapabilityStatus.DOWNLOADABLE)
            } else capability(task)
        }
        val old = backgroundScope.launch { fixture.cache.refresh(AiTask.ASK) }
        runCurrent()
        val full = backgroundScope.launch { fixture.cache.refresh(force = true) }
        runCurrent()
        releaseOld.complete(Unit)
        runCurrent()
        old.join()
        full.join()
        assertThat(fixture.cache.snapshot.value[AiTask.ASK].status).isEqualTo(AiCapabilityStatus.AVAILABLE)
    }

    @Test fun `older task result cannot overwrite a newer same-task refresh`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val releaseOld = CompletableDeferred<Unit>()
        var asks = 0
        fixture.resolve = { task ->
            if (++asks == 1) {
                releaseOld.await()
                capability(task, AiCapabilityStatus.DOWNLOADABLE)
            } else capability(task)
        }
        val old = backgroundScope.launch { fixture.cache.refresh(AiTask.ASK) }
        runCurrent()
        val newer = backgroundScope.launch { fixture.cache.refresh(AiTask.ASK) }
        runCurrent()
        releaseOld.complete(Unit)
        runCurrent()
        old.join()
        newer.join()
        assertThat(fixture.cache.snapshot.value[AiTask.ASK].status).isEqualTo(AiCapabilityStatus.AVAILABLE)
        assertThat(asks).isEqualTo(2)
    }

    @Test fun `cancelled task queued behind a full check never starts a probe`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val releaseFirst = CompletableDeferred<Unit>()
        fixture.resolve = { task ->
            if (fixture.calls.size == 1) releaseFirst.await()
            capability(task)
        }
        val full = backgroundScope.launch { fixture.cache.refresh(force = true) }
        runCurrent()
        val task = backgroundScope.launch { fixture.cache.refresh(AiTask.ASK) }
        runCurrent()
        task.cancelAndJoin()
        releaseFirst.complete(Unit)
        runCurrent()
        full.join()
        assertThat(fixture.calls).containsExactlyElementsIn(AiTask.entries).inOrder()
    }

    @Test fun `fresh full snapshot is reused until exact expiry and force bypasses cache`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.cache.refresh(force = false)
        fixture.calls.clear()
        fixture.advance(59_999)
        fixture.cache.refresh(force = false)
        assertThat(fixture.calls).isEmpty()
        fixture.advance(1)
        fixture.cache.refresh(force = false)
        assertThat(fixture.calls).hasSize(AiTask.entries.size)
        fixture.cache.refresh(force = true)
        assertThat(fixture.calls).hasSize(AiTask.entries.size * 2)
    }

    @Test fun `cancelled full refresh preserves prior task snapshot and does not claim full freshness`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.cache.refresh(AiTask.ASK)
        val before = fixture.cache.snapshot.value
        val release = CompletableDeferred<Unit>()
        fixture.resolve = { task -> release.await(); capability(task) }
        val refresh = backgroundScope.launch { fixture.cache.refresh(force = true) }
        runCurrent()
        assertThat(fixture.cache.snapshot.value.isRefreshing).isTrue()
        refresh.cancelAndJoin()
        assertThat(fixture.cache.snapshot.value).isEqualTo(before)
        release.complete(Unit)
        fixture.calls.clear()
        fixture.cache.refresh(force = false)
        assertThat(fixture.calls).containsExactlyElementsIn(AiTask.entries).inOrder()
    }

    @Test fun `failed full refresh clears loading preserves old results and can be retried`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.cache.refresh(force = false)
        val before = fixture.cache.snapshot.value
        fixture.advance(60_001)
        val failure = IllegalStateException("provider failed")
        fixture.resolve = { throw failure }
        val caught = runCatching { fixture.cache.refresh(force = true) }.exceptionOrNull()
        assertThat(caught).isInstanceOf(IllegalStateException::class.java)
        assertThat(caught?.message).isEqualTo("provider failed")
        assertThat(fixture.cache.snapshot.value).isEqualTo(before)
        fixture.resolve = { capability(it, AiCapabilityStatus.DOWNLOADABLE) }
        fixture.cache.refresh(force = false)
        assertThat(fixture.cache.snapshot.value[AiTask.ASK].status).isEqualTo(AiCapabilityStatus.DOWNLOADABLE)
        assertThat(fixture.cache.snapshot.value.isRefreshing).isFalse()
    }

    @Test fun `overlapping cached full refreshes share the first completed snapshot`() = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        fixture.resolve = { task -> release.await(); capability(task) }
        val first = backgroundScope.launch { fixture.cache.refresh(force = false) }
        runCurrent()
        val second = backgroundScope.launch { fixture.cache.refresh(force = false) }
        runCurrent()
        assertThat(fixture.calls).hasSize(1)
        release.complete(Unit)
        runCurrent()
        first.join()
        second.join()
        assertThat(fixture.calls).containsExactlyElementsIn(AiTask.entries).inOrder()
        assertThat(fixture.cache.snapshot.value.isRefreshing).isFalse()
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        var wallMillis = 100_000L
        var elapsedMillis = 0L
        val calls = mutableListOf<AiTask>()
        var resolve: suspend (AiTask) -> AiCapability = { capability(it) }
        val cache = CapabilitySnapshotCache(
            ioDispatcher = dispatcher,
            resolve = { task -> calls += task; resolve(task) },
            wallClockMillis = { wallMillis },
            elapsedRealtimeMillis = { elapsedMillis },
        )
        fun advance(millis: Long) { wallMillis += millis; elapsedMillis += millis }
    }

    companion object {
        private fun capability(task: AiTask, status: AiCapabilityStatus = AiCapabilityStatus.AVAILABLE) =
            AiCapability(task, status, AiProvider.GEMINI_NANO)
    }
}
