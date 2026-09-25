package com.localai.toolkit.feature.common

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.fake.FakeAiEngine
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import com.localai.toolkit.testing.TestCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenAiFeatureGateTest {
    @Test fun `rapid download taps start one download and completion rechecks capability`() = runTest {
        val done = CompletableDeferred<Unit>()
        var calls = 0
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                calls++
                emit(ModelDownloadState.Started(null))
                done.await()
                emit(ModelDownloadState.Completed)
            }
        }
        val capabilities = TestCapabilities()
        val gate = GenAiFeatureGate(AiTask.SUMMARIZE, capabilities, engine, backgroundScope)
        gate.download()
        gate.download()
        assertThat(gate.downloadState.value).isInstanceOf(ModelDownloadState.Started::class.java)
        runCurrent()
        gate.download()
        runCurrent()
        assertThat(calls).isEqualTo(1)
        done.complete(Unit)
        runCurrent()
        assertThat(capabilities.refreshedTasks).containsExactly(AiTask.SUMMARIZE)
    }

    @Test fun `failed download can be retried`() = runTest {
        var calls = 0
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                calls++
                emit(if (calls == 1) ModelDownloadState.Failed(AiFailure.DownloadFailed()) else ModelDownloadState.Completed)
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, TestCapabilities(), engine, backgroundScope)
        gate.download()
        runCurrent()
        assertThat(gate.downloadState.value).isInstanceOf(ModelDownloadState.Failed::class.java)
        gate.download()
        runCurrent()
        assertThat(calls).isEqualTo(2)
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.Completed)
    }

    @Test fun `synchronous download setup failure is visible and Retry starts a new attempt`() = runTest {
        var calls = 0
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask): Flow<ModelDownloadState> {
                calls++
                if (calls == 1) error("client creation failed")
                return flowOf(ModelDownloadState.Completed)
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, Checks(), engine, backgroundScope)
        runCurrent()
        gate.download()
        runCurrent()
        assertThat(gate.downloadState.value).isInstanceOf(ModelDownloadState.Failed::class.java)
        gate.download()
        runCurrent()
        assertThat(calls).isEqualTo(2)
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.Completed)
    }

    @Test fun `unexpected download stream exception preserves a typed failure and leaves progress`() = runTest {
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                emit(ModelDownloadState.InProgress(10, 100))
                throw AiException(AiFailure.NotEnoughStorage("test storage"))
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, Checks(), engine, backgroundScope)
        gate.download()
        runCurrent()
        assertThat((gate.downloadState.value as ModelDownloadState.Failed).reason)
            .isInstanceOf(AiFailure.NotEnoughStorage::class.java)
    }

    @Test fun `empty download stream becomes a failure instead of endless progress`() = runTest {
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = emptyFlow<ModelDownloadState>()
        }
        val gate = GenAiFeatureGate(AiTask.ASK, Checks(), engine, backgroundScope)
        gate.download()
        runCurrent()
        assertThat(gate.downloadState.value).isInstanceOf(ModelDownloadState.Failed::class.java)
    }

    @Test fun `Completed ends the upstream stream before checking the installed model`() = runTest {
        var closed = false
        val checks = Checks().apply { onTaskCheck = { assertThat(closed).isTrue() } }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                try { emit(ModelDownloadState.Completed); awaitCancellation() }
                finally { closed = true }
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        runCurrent()
        gate.download()
        runCurrent()
        assertThat(closed).isTrue()
        assertThat(checks.taskChecks).isEqualTo(1)
    }

    @Test fun `failed initial check shows Retry and successful check recovers`() = runTest {
        val checks = Checks().apply { initialFails = true }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, FakeAiEngine(), backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.ERROR)
        gate.refresh()
        runCurrent()
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.AVAILABLE)
    }

    @Test fun `failed post-download check keeps completion and Retry only checks status`() = runTest {
        var downloads = 0
        val checks = Checks().apply { taskFails = true }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask): Flow<ModelDownloadState> {
                downloads++
                return flowOf(ModelDownloadState.Completed)
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        gate.download()
        runCurrent()
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.Completed)
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.ERROR)
        checks.taskFails = false
        gate.refresh()
        runCurrent()
        assertThat(downloads).isEqualTo(1)
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.AVAILABLE)
    }

    @Test fun `terminal Failed closes upstream so another download can start`() = runTest {
        var calls = 0
        var closed = 0
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                calls++
                try { emit(ModelDownloadState.Failed(AiFailure.DownloadFailed())); awaitCancellation() }
                finally { closed++ }
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, Checks(), engine, backgroundScope)
        gate.download()
        runCurrent()
        gate.download()
        runCurrent()
        assertThat(calls).isEqualTo(2)
        assertThat(closed).isEqualTo(2)
    }

    @Test fun `repeated status checks coalesce and show checking until finished`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val checks = Checks().apply { onTaskCheck = { waiting.await() } }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, FakeAiEngine(), backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        gate.refresh()
        gate.refresh()
        runCurrent()
        assertThat(checks.taskChecks).isEqualTo(1)
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.UNKNOWN)
        waiting.complete(Unit)
        runCurrent()
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.AVAILABLE)
    }

    @Test fun `failed Check status keeps download progress and exposes retryable check error`() = runTest {
        var closed = false
        val checks = Checks().apply { taskFails = true }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                try { emit(ModelDownloadState.InProgress(30, 100)); awaitCancellation() }
                finally { closed = true }
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        gate.download()
        runCurrent()
        gate.refresh()
        runCurrent()
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.InProgress(30, 100))
        assertThat(gateStateOf(gate.capability.value, gate.downloadState.value)).isEqualTo(GateState.DOWNLOADING_CHECK_FAILED)
        assertThat(closed).isFalse()
        checks.taskFails = false
        gate.refresh()
        runCurrent()
        assertThat(closed).isTrue()
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.Completed)
        assertThat(gateStateOf(gate.capability.value, gate.downloadState.value)).isEqualTo(GateState.READY)
    }

    @Test fun `Check status can stop waiting for a lost completion callback`() = runTest {
        var closed = false
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                try { emit(ModelDownloadState.Started(100)); awaitCancellation() }
                finally { closed = true }
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, Checks(), engine, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        gate.download()
        runCurrent()
        gate.refresh()
        runCurrent()
        assertThat(closed).isTrue()
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.Completed)
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.AVAILABLE)
    }

    @Test fun `Check status reporting downloading keeps the live request`() = runTest {
        var closed = false
        val checks = Checks().apply { taskStatus = AiCapabilityStatus.DOWNLOADING }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                try { emit(ModelDownloadState.InProgress(20, 100)); awaitCancellation() }
                finally { closed = true }
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        runCurrent()
        gate.download()
        runCurrent()
        gate.refresh()
        runCurrent()
        assertThat(closed).isFalse()
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.InProgress(20, 100))
    }

    @Test fun `an older status check cannot cancel a newer download`() = runTest {
        var closed = false
        val waiting = CompletableDeferred<Unit>()
        val checks = Checks().apply { onTaskCheck = { waiting.await() } }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                try { emit(ModelDownloadState.Started(100)); awaitCancellation() }
                finally { closed = true }
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        runCurrent()
        gate.refresh()
        runCurrent()
        gate.download()
        runCurrent()
        waiting.complete(Unit)
        runCurrent()
        assertThat(closed).isFalse()
        assertThat(gate.downloadState.value).isInstanceOf(ModelDownloadState.Started::class.java)
    }

    @Test fun `release cancels owned work catches close errors and ignores later button calls`() = runTest {
        var releases = 0
        var downloadClosed = false
        var checkClosed = false
        val checks = Checks().apply { onTaskCheck = { try { awaitCancellation() } finally { checkClosed = true } } }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                try { emit(ModelDownloadState.Started(100)); awaitCancellation() }
                finally { downloadClosed = true }
            }
            override fun release(task: AiTask?) { releases++; error("native close failed") }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        runCurrent()
        gate.download()
        gate.refresh()
        runCurrent()
        gate.release()
        gate.release()
        gate.download()
        gate.refresh()
        runCurrent()
        assertThat(releases).isEqualTo(1)
        assertThat(downloadClosed).isTrue()
        assertThat(checkClosed).isTrue()
        assertThat(checks.taskChecks).isEqualTo(1)
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.Idle)
    }

    @Test fun `a completed check with no capability result offers Retry instead of endless checking`() = runTest {
        val checks = Checks().apply { taskStatus = AiCapabilityStatus.UNKNOWN }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, FakeAiEngine(), backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        gate.refresh()
        runCurrent()
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.ERROR)
    }

    @Test fun `post-download check waits for cancelled initial check cleanup before publishing readiness`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val checks = Checks().apply {
            onInitialCheck = {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await(); set(AiCapabilityStatus.DOWNLOADABLE) } }
            }
        }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flowOf(ModelDownloadState.Completed)
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        gate.download()
        runCurrent()
        try {
            assertThat(checks.taskChecks).isEqualTo(0)
            cleanup.complete(Unit)
            runCurrent()
            assertThat(checks.taskChecks).isEqualTo(1)
            assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.AVAILABLE)
        } finally { cleanup.complete(Unit); gate.release(); runCurrent() }
    }

    @Test fun `Check status reporting unsupported ends the stale local download`() = runTest {
        var closed = false
        val checks = Checks().apply { taskStatus = AiCapabilityStatus.UNSUPPORTED }
        val engine = object : AiEngine by FakeAiEngine() {
            override fun downloadModel(task: AiTask) = flow {
                try { emit(ModelDownloadState.Started(100)); awaitCancellation() }
                finally { closed = true }
            }
        }
        val gate = GenAiFeatureGate(AiTask.ASK, checks, engine, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.capability.collect { } }
        runCurrent()
        gate.download()
        runCurrent()
        gate.refresh()
        runCurrent()
        assertThat(closed).isTrue()
        assertThat(gate.downloadState.value).isEqualTo(ModelDownloadState.Idle)
        assertThat(gate.capability.value.status).isEqualTo(AiCapabilityStatus.UNSUPPORTED)
    }

    private class Checks : DeviceAiCapabilityManager {
        override val snapshot = MutableStateFlow(DeviceAiSnapshot())
        var initialFails = false
        var taskFails = false
        var taskChecks = 0
        var taskStatus = AiCapabilityStatus.AVAILABLE
        var onInitialCheck: suspend () -> Unit = { }
        var onTaskCheck: suspend () -> Unit = { }
        override suspend fun refresh(force: Boolean) {
            onInitialCheck()
            if (initialFails) error("initial status failed")
            set(AiCapabilityStatus.DOWNLOADABLE)
        }
        override suspend fun refresh(task: AiTask) {
            taskChecks++
            onTaskCheck()
            if (taskFails) error("status service failed")
            set(taskStatus)
        }
        fun set(status: AiCapabilityStatus) {
            snapshot.value = DeviceAiSnapshot(mapOf(AiTask.ASK to AiCapability(AiTask.ASK, status, AiProvider.GEMINI_NANO)))
        }
    }
}
