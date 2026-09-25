package com.localai.toolkit.feature.image

import android.graphics.Bitmap
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.AiStreamChunk
import com.localai.toolkit.ai.engine.AnalyzeImageRequest
import com.localai.toolkit.ai.engine.DescribeImageRequest
import com.localai.toolkit.ai.fake.FakeAiEngine
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.ImageLoader
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ImageViewModelTest {
    @Test fun `rejected question retains its image and input for another run`() = runTest {
        var attempts = 0
        val rejected = AiFailure.InvalidImage()
        val engine = object : AiEngine by FakeAiEngine() {
            override fun analyzeImage(request: AnalyzeImageRequest) = flow {
                if (++attempts == 1) throw AiException(rejected)
                assertThat(request.bitmap).isSameInstanceAs(image)
                assertThat(request.prompt).isEqualTo("What is here?")
                emit(AiStreamChunk("Recovered image answer", true))
            }
        }
        val vm = model(ImageLoader { _, _ -> image }, engine)
        vm.onImageSelected(first)
        runCurrent()
        vm.onModeChange(ImageMode.ASK)
        vm.onQuestionChange("What is here?")
        vm.onRun()
        runCurrent()
        assertThat(vm.uiState.value.failure).isEqualTo(rejected)
        assertThat(vm.uiState.value.isWorking).isFalse()
        assertThat(vm.uiState.value.isStreaming).isFalse()
        assertThat(vm.uiState.value.canRun).isTrue()
        assertThat(vm.uiState.value.preview).isSameInstanceAs(image)
        assertThat(vm.uiState.value.imageUri).isEqualTo(first)
        assertThat(vm.uiState.value.question).isEqualTo("What is here?")
        vm.onRun()
        runCurrent()
        assertThat(attempts).isEqualTo(2)
        assertThat(vm.uiState.value.failure).isNull()
        assertThat(vm.uiState.value.output).isEqualTo("Recovered image answer")
    }

    @get:Rule val main = MainDispatcherRule()
    private val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val first = Uri.parse("content://test/first")
    private val second = Uri.parse("content://test/second")
    private fun model(loader: ImageLoader, engine: AiEngine = FakeAiEngine(), history: MemoryHistory = MemoryHistory()) = main.own(
        ImageViewModel(loader, engine, history, ToolHandoff(), TestCapabilities(), MemorySettings()),
    )

    @Test fun `deleted image description can be saved again without rerunning inference`() = runTest {
        val history = MemoryHistory()
        val vm = model(ImageLoader { _, _ -> image }, history = history)
        vm.onImageSelected(first)
        runCurrent()
        vm.onRun()
        runCurrent()
        val output = vm.uiState.value.output
        assertThat(output).isNotEmpty()
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        history.deleteAll()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        assertThat(vm.uiState.value.output).isEqualTo(output)
        vm.onSave()
        runCurrent()
        assertThat(history.items.value.single().output).isEqualTo(output)
    }

    @Test fun `clear during image decode ignores even an uncancellable late bitmap`() = runTest {
        val pending = CompletableDeferred<Bitmap?>()
        val vm = model(ImageLoader { _, _ -> withContext(NonCancellable) { pending.await() } })
        vm.onImageSelected(first)
        runCurrent()
        assertThat(vm.uiState.value.isLoadingImage).isTrue()
        vm.onClear()
        pending.complete(image)
        runCurrent()
        assertThat(vm.uiState.value).isEqualTo(ImageUiState())
    }

    @Test fun `new selection wins over older pending image`() = runTest {
        val pending = CompletableDeferred<Bitmap?>()
        val replacement = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val vm = model(ImageLoader { uri, _ ->
            if (uri == first) withContext(NonCancellable) { pending.await() } else replacement
        })
        vm.onImageSelected(first)
        runCurrent()
        vm.onImageSelected(second)
        runCurrent()
        pending.complete(image)
        runCurrent()
        assertThat(vm.uiState.value.imageUri).isEqualTo(second)
        assertThat(vm.uiState.value.preview).isSameInstanceAs(replacement)
    }

    @Test fun `changing mode cancels description without populating the question answer`() = runTest {
        val pending = CompletableDeferred<String>()
        val engine = object : AiEngine by FakeAiEngine() {
            override suspend fun describeImage(request: DescribeImageRequest) = pending.await()
        }
        val vm = model(ImageLoader { _, _ -> image }, engine)
        vm.onImageSelected(first)
        runCurrent()
        vm.onRun()
        runCurrent()
        assertThat(vm.uiState.value.isWorking).isTrue()
        vm.onModeChange(ImageMode.ASK)
        pending.complete("old description")
        runCurrent()
        assertThat(vm.uiState.value.mode).isEqualTo(ImageMode.ASK)
        assertThat(vm.uiState.value.output).isEmpty()
        assertThat(vm.uiState.value.isWorking).isFalse()
    }

    @Test fun `stop keeps partial answer and clear does not recycle displayed bitmap`() = runTest {
        val vm = model(ImageLoader { _, _ -> image }, FakeAiEngine(chunkDelayMillis = 100))
        vm.onImageSelected(first)
        runCurrent()
        vm.onModeChange(ImageMode.ASK)
        vm.onQuestionChange("What is in the image?")
        vm.onRun()
        advanceTimeBy(201)
        runCurrent()
        val partial = vm.uiState.value.output
        assertThat(partial).isNotEmpty()
        vm.onStop()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(vm.uiState.value.output).isEqualTo(partial)
        assertThat(vm.uiState.value.canRun).isTrue()
        vm.onClear()
        assertThat(image.isRecycled).isFalse()
        assertThat(vm.uiState.value.hasImage).isFalse()
    }

    @Test fun `editing a question clears the old answer and saved status`() = runTest {
        val vm = model(ImageLoader { _, _ -> image })
        vm.onImageSelected(first)
        runCurrent()
        vm.onModeChange(ImageMode.ASK)
        vm.onQuestionChange("First question")
        vm.onRun()
        runCurrent()
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        vm.onQuestionChange("Second question")
        assertThat(vm.uiState.value.output).isEmpty()
        assertThat(vm.uiState.value.savedToHistory).isFalse()
    }

    @Test fun `invalid image exits loading and exposes an error`() = runTest {
        val vm = model(ImageLoader { _, _ -> null })
        vm.onImageSelected(first)
        runCurrent()
        assertThat(vm.uiState.value.isLoadingImage).isFalse()
        assertThat(vm.uiState.value.failure).isNotNull()
        assertThat(vm.uiState.value.canRun).isFalse()
    }
}
