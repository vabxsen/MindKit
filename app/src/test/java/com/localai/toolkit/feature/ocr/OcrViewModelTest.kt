package com.localai.toolkit.feature.ocr

import android.graphics.Bitmap
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.mlkit.OcrEngine
import com.localai.toolkit.ai.mlkit.RecognizedText
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.ImageLoader
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OcrViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val uri = Uri.parse("content://test/photo")
    private val history = MemoryHistory()
    private val handoff = ToolHandoff()
    private val engine = TestOcr()
    private fun model(loader: ImageLoader = ImageLoader { _, _ -> image }) = main.own(
        OcrViewModel(loader, engine, history, handoff, MemorySettings()),
    )

    @Test fun `deleted OCR result can be saved again without recognizing the image again`() = runTest {
        val vm = model()
        vm.onImageSelected(uri)
        runCurrent()
        vm.onSave()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isTrue()
        history.deleteAll()
        runCurrent()
        assertThat(vm.uiState.value.savedToHistory).isFalse()
        vm.onSave()
        runCurrent()
        assertThat(history.items.value.single().output).isEqualTo("Recognized words")
        assertThat(engine.calls).isEqualTo(1)
    }

    @Test fun `clear during decoding cannot restore a late preview or result`() = runTest {
        val pending = CompletableDeferred<Bitmap?>()
        val vm = model(ImageLoader { _, _ -> withContext(NonCancellable) { pending.await() } })
        vm.onImageSelected(uri)
        runCurrent()
        vm.onClear()
        pending.complete(image)
        runCurrent()
        assertThat(vm.uiState.value).isEqualTo(OcrUiState())
        assertThat(engine.calls).isEqualTo(0)
    }

    @Test fun `clear during recognition preserves native bitmap lifetime and rejects late text`() = runTest {
        engine.result = CompletableDeferred()
        val vm = model()
        vm.onImageSelected(uri)
        runCurrent()
        vm.onClear()
        runCurrent()
        assertThat(image.isRecycled).isFalse()
        engine.result.complete(RecognizedText("late text", listOf("late text")))
        runCurrent()
        assertThat(vm.uiState.value.extractedText).isEmpty()
        assertThat(vm.uiState.value.failure).isNull()
    }

    @Test fun `retry repeats the selected image after an inference failure`() = runTest {
        engine.failure = AiFailure.Busy()
        val vm = model()
        vm.onImageSelected(uri)
        runCurrent()
        assertThat(vm.uiState.value.failure).isNotNull()
        engine.failure = null
        vm.onRetry()
        runCurrent()
        assertThat(engine.calls).isEqualTo(2)
        assertThat(vm.uiState.value.extractedText).isEqualTo("Recognized words")
        assertThat(vm.uiState.value.failure).isNull()
    }

    @Test fun `empty and unreadable images leave loading with meaningful states`() = runTest {
        val invalid = model(ImageLoader { _, _ -> null })
        invalid.onImageSelected(uri)
        runCurrent()
        assertThat(invalid.uiState.value.failure).isInstanceOf(AiFailure.InvalidImage::class.java)
        assertThat(invalid.uiState.value.isRecognizing).isFalse()
        engine.result = CompletableDeferred(RecognizedText("", emptyList()))
        val empty = model()
        empty.onImageSelected(uri)
        runCurrent()
        assertThat(empty.uiState.value.noTextDetected).isTrue()
        assertThat(empty.uiState.value.isRecognizing).isFalse()
    }

    @Test fun `shared image recognition can save once and hand text to another tool`() = runTest {
        handoff.send(HandoffPayload(ToolId.OCR, imageUri = uri))
        val vm = model()
        runCurrent()
        vm.onSave()
        vm.onSave()
        runCurrent()
        vm.onSave()
        runCurrent()
        assertThat(history.items.value).hasSize(1)
        assertThat(history.items.value.single().output).isEqualTo("Recognized words")
        vm.sendTo(ToolId.SUMMARIZE)
        assertThat(handoff.consume(ToolId.SUMMARIZE)?.text).isEqualTo("Recognized words")
        vm.onImageSelected(Uri.parse("content://test/replacement"))
        assertThat(image.isRecycled).isFalse()
    }

    private class TestOcr : OcrEngine {
        var result = CompletableDeferred(RecognizedText("Recognized words", listOf("Recognized words")))
        var failure: AiFailure? = null
        var calls = 0
        override suspend fun recognize(bitmap: Bitmap): RecognizedText {
            calls++
            failure?.let { throw AiException(it) }
            return withContext(NonCancellable) { result.await() }
        }
        override fun close() = Unit
    }
}
