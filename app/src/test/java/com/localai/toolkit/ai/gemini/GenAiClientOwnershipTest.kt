package com.localai.toolkit.ai.gemini

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.localai.toolkit.ai.engine.AskRequest
import com.localai.toolkit.ai.engine.AskRole
import com.localai.toolkit.ai.engine.AskTurn
import com.localai.toolkit.ai.capability.DefaultDeviceAiCapabilityManager
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Real provider/engine paths; only the SDK factory supplies controlled clients. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [GenAiClientOwnershipTest.ShadowGeneration::class])
@OptIn(ExperimentalCoroutinesApi::class)
class GenAiClientOwnershipTest {
    @Before fun resetClients() { ShadowGeneration.created.clear() }

    // Controlled traversal faults exercise the real request builder without pretending
    // that Robolectric can validate native SDK image construction or input limits.
    private fun requestHistory(read: () -> AskTurn) = object : AbstractList<AskTurn>() {
        override val size = 1
        override fun get(index: Int): AskTurn = read()
    }

    @Test fun `request setup is cold and repeated for each collection`() = runTest {
        val clients = GenAiClientProvider(ApplicationProvider.getApplicationContext())
        val engine = GeminiNanoAiEngine(clients, StandardTestDispatcher(testScheduler))
        var reads = 0
        val response = engine.generateText(AskRequest("Hello", requestHistory {
            reads++
            AskTurn(AskRole.USER, "Earlier question")
        }))
        assertThat(reads).isEqualTo(0)
        assertThat(ShadowGeneration.created).isEmpty()
        try {
            repeat(2) { iteration ->
                val inference = backgroundScope.launch { response.collect() }
                runCurrent()
                assertThat(reads).isEqualTo(iteration + 1)
                inference.cancelAndJoin()
            }
        } finally { clients.close(null) }
    }

    @Test fun `request construction failure is mapped before allocating a client`() = runTest {
        val clients = GenAiClientProvider(ApplicationProvider.getApplicationContext())
        val engine = GeminiNanoAiEngine(clients, StandardTestDispatcher(testScheduler))
        val rejected = GenAiException("Rejected setup", null, GenAiException.ErrorCode.REQUEST_TOO_LARGE)
        val caught = runCatching {
            engine.generateText(AskRequest("Hello", requestHistory { throw rejected })).collect()
        }.exceptionOrNull()
        assertThat(caught).isInstanceOf(AiException::class.java)
        assertThat((caught as AiException).failure).isInstanceOf(AiFailure.InvalidInput::class.java)
        assertThat((caught.failure as AiFailure.InvalidInput).tooLarge).isTrue()
        assertThat(caught.cause).isSameInstanceAs(rejected)
        assertThat(ShadowGeneration.created).isEmpty()
    }

    @Test fun `request construction cancellation is not mapped into a user error`() = runTest {
        val clients = GenAiClientProvider(ApplicationProvider.getApplicationContext())
        val engine = GeminiNanoAiEngine(clients, StandardTestDispatcher(testScheduler))
        val cancelled = CancellationException("Request cancelled")
        val caught = runCatching {
            engine.generateText(AskRequest("Hello", requestHistory { throw cancelled })).collect()
        }.exceptionOrNull()
        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(caught?.message).isEqualTo(cancelled.message)
        assertThat(ShadowGeneration.created).isEmpty()
    }

    @Test fun `collector failure is not mistaken for an engine failure`() = runTest {
        val clients = GenAiClientProvider(ApplicationProvider.getApplicationContext())
        val engine = GeminiNanoAiEngine(clients, StandardTestDispatcher(testScheduler))
        val downstream = IllegalStateException("Collector failed")
        var caught: Throwable? = null
        val inference = backgroundScope.launch {
            caught = runCatching {
                engine.generateText(AskRequest("Hello")).collect { throw downstream }
            }.exceptionOrNull()
        }
        runCurrent()
        val active = ShadowGeneration.created.single()
        clients.close(null)
        active.finishGeneration.complete(Unit)
        runCurrent()
        inference.join()
        // Coroutine stack-trace recovery may copy an exception across flowOn.
        assertThat(caught).isInstanceOf(IllegalStateException::class.java)
        assertThat(caught?.message).isEqualTo(downstream.message)
        assertThat(active.closes).isEqualTo(1)
    }

    @Test fun `image question release cannot close a model still serving Ask`() = runTest {
        val clients = GenAiClientProvider(ApplicationProvider.getApplicationContext())
        val engine = GeminiNanoAiEngine(clients, StandardTestDispatcher(testScheduler))
        val inference = backgroundScope.launch {
            engine.generateText(AskRequest("Hello")).collect()
        }
        runCurrent()
        val active = ShadowGeneration.created.single()
        try {
            assertThat(active.streams).isEqualTo(1)
            engine.release(AiTask.IMAGE_QUESTION)
            assertThat(active.closes).isEqualTo(0)
            inference.cancelAndJoin()
            assertThat(active.streams).isEqualTo(0)
            assertThat(active.closes).isEqualTo(1)
        } finally {
            inference.cancelAndJoin()
            clients.close(null)
        }
    }

    @Test fun `screen release waits for both prompt inference and download`() = runTest {
        val clients = GenAiClientProvider(ApplicationProvider.getApplicationContext())
        val engine = GeminiNanoAiEngine(clients, StandardTestDispatcher(testScheduler))
        val inference = backgroundScope.launch { engine.generateText(AskRequest("Hello")).collect() }
        val download = backgroundScope.launch { engine.downloadModel(AiTask.ASK).collect() }
        runCurrent()
        val active = ShadowGeneration.created.single()
        try {
            assertThat(active.streams).isEqualTo(1)
            assertThat(active.downloads).isEqualTo(1)
            engine.release(AiTask.ASK)
            assertThat(active.closes).isEqualTo(0)
            inference.cancelAndJoin()
            assertThat(active.closes).isEqualTo(0)
            download.cancelAndJoin()
            assertThat(active.downloads).isEqualTo(0)
            assertThat(active.closes).isEqualTo(1)
            engine.release(AiTask.ASK)
            assertThat(active.closes).isEqualTo(1)
        } finally {
            inference.cancelAndJoin()
            download.cancelAndJoin()
            clients.close(null)
        }
    }

    @Test fun `capability lookup holds its lease until cancellation unwinds`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clients = GenAiClientProvider(context)
        val capabilities = DefaultDeviceAiCapabilityManager(
            context, clients, unusedSpeech(), StandardTestDispatcher(testScheduler),
        )
        val check = backgroundScope.launch { capabilities.refresh(AiTask.ASK) }
        runCurrent()
        val active = ShadowGeneration.created.single()
        try {
            clients.close(AiTask.IMAGE_QUESTION)
            assertThat(active.closes).isEqualTo(0)
            check.cancelAndJoin()
            assertThat(active.closes).isEqualTo(1)
            assertThat(capabilities.snapshot.value.capabilities).doesNotContainKey(AiTask.ASK)
        } finally {
            check.cancelAndJoin()
            clients.close(null)
        }
    }

    @Test fun `SDK model-name cancellation is not swallowed as a successful capability check`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clients = GenAiClientProvider(context)
        val capabilities = DefaultDeviceAiCapabilityManager(
            context, clients, unusedSpeech(), StandardTestDispatcher(testScheduler),
        )
        val check = backgroundScope.launch { capabilities.refresh(AiTask.ASK) }
        runCurrent()
        val active = ShadowGeneration.created.single()
        clients.close(AiTask.ASK)
        active.modelName.completeExceptionally(CancellationException("SDK cancelled"))
        runCurrent()
        check.join()
        assertThat(check.isCancelled).isTrue()
        assertThat(capabilities.snapshot.value.capabilities).doesNotContainKey(AiTask.ASK)
        assertThat(active.closes).isEqualTo(1)
    }

    @Test fun `SDK stream cancellation releases retired lease without becoming an AI failure`() = runTest {
        val clients = GenAiClientProvider(ApplicationProvider.getApplicationContext())
        val engine = GeminiNanoAiEngine(clients, StandardTestDispatcher(testScheduler))
        var caught: Throwable? = null
        val inference = backgroundScope.launch {
            caught = runCatching { engine.generateText(AskRequest("Hello")).collect() }.exceptionOrNull()
        }
        runCurrent()
        val active = ShadowGeneration.created.single()
        val cancellation = CancellationException("SDK cancelled")
        active.generationFailure = cancellation
        clients.close(null)
        active.finishGeneration.complete(Unit)
        runCurrent()
        inference.join()
        assertThat(caught).isInstanceOf(CancellationException::class.java)
        assertThat(caught?.message).isEqualTo("SDK cancelled")
        assertThat(active.closes).isEqualTo(1)
    }

    private fun unusedSpeech(): TranscriptionEngine = Proxy.newProxyInstance(
        TranscriptionEngine::class.java.classLoader, arrayOf(TranscriptionEngine::class.java),
    ) { _, method, _ -> error("Unexpected speech call: ${method.name}") } as TranscriptionEngine

    class PromptClient {
        var closes = 0
        var streams = 0
        var downloads = 0
        val finishGeneration = CompletableDeferred<Unit>()
        val modelName = CompletableDeferred<String>()
        var generationFailure: Exception? = null
        @Suppress("UNCHECKED_CAST")
        val sdk: GenerativeModel = Proxy.newProxyInstance(
            GenerativeModel::class.java.classLoader, arrayOf(GenerativeModel::class.java),
        ) { _, method, args ->
            when (method.name) {
                "generateContentStream" -> flow<GenerateContentResponse> {
                    streams++
                    try {
                        finishGeneration.await()
                        generationFailure?.let { throw it }
                    } finally { streams-- }
                }
                "download" -> flow<DownloadStatus> {
                    downloads++
                    try { awaitCancellation() } finally { downloads-- }
                }
                "checkStatus" -> FeatureStatus.AVAILABLE
                "getBaseModelName" -> (suspend { modelName.await() })
                    .startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<String>)
                "close" -> { closes++; null }
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as GenerativeModel
    }

    @Implements(Generation::class, isInAndroidSdk = false)
    class ShadowGeneration {
        @Implementation fun getClient(): GenerativeModel =
            PromptClient().also { created += it }.sdk

        companion object { val created = mutableListOf<PromptClient>() }
    }
}
