package com.localai.toolkit.ai.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled ML Kit models, not fake engines. No model download is requested. */
@RunWith(AndroidJUnit4::class)
class NativeMlKitInstrumentedTest {
    @Test fun bundledOcrRecognizesTextOnFirstUse() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 320, Bitmap.Config.ARGB_8888)
        val engine = MlKitOcrEngine(Dispatchers.IO)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("MINDKIT TEST 123", 50f, 170f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 90f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                })
            }
            val result = withTimeout(30_000) { engine.recognize(bitmap) }
            assertThat(result.fullText).contains("MINDKIT TEST 123")
            assertThat(result.blocks).isNotEmpty()
            assertThat(result.isEmpty).isFalse()
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }

    @Test fun bundledOcrReturnsEmptyForBlankImage() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val engine = MlKitOcrEngine(Dispatchers.IO)
        try {
            bitmap.eraseColor(Color.WHITE)
            val result = withTimeout(30_000) { engine.recognize(bitmap) }
            assertThat(result.isEmpty).isTrue()
            assertThat(result.blocks).isEmpty()
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }

    @Test fun bundledLanguageIdentificationRecognizesFrenchAndIgnoresBlankText() = runBlocking {
        val engine = MlKitTranslationEngine(Dispatchers.IO)
        try {
            val language = withTimeout(30_000) {
                engine.detectLanguage("Bonjour, je voudrais traduire ce texte en anglais. Merci beaucoup pour votre aide.")
            }
            assertThat(language).isEqualTo("fr")
            assertThat(engine.detectLanguage("   ")).isNull()
        } finally {
            engine.close()
        }
    }
}
