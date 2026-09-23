package com.localai.toolkit.ai.mlkit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** What text recognition produced. */
data class RecognizedText(
    val fullText: String,
    /** Blocks in reading order, useful for preserving paragraph breaks. */
    val blocks: List<String>,
) {
    val isEmpty: Boolean get() = fullText.isBlank()
}

/**
 * On-device text recognition.
 *
 * Kept behind an interface for the same reason as [com.localai.toolkit.ai.engine.AiEngine]:
 * feature code and tests never touch an ML Kit type.
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): RecognizedText

    /** Releases the recogniser. Safe to call repeatedly. */
    fun close()
}

/**
 * ML Kit Text Recognition v2, Latin script.
 *
 * The bundled variant is used deliberately: the model ships inside the APK, so extracting
 * text works on first launch, with no Play services module download and no network. That
 * is what lets the OCR tool keep its "works offline" claim honest.
 */
@Singleton
class MlKitOcrEngine @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : OcrEngine {

    // Created lazily so simply opening the app does not spin up a recogniser.
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(bitmap: Bitmap): RecognizedText = withContext(ioDispatcher) {
        try {
            // Rotation is already baked in by the EXIF handling in decodeDownsampledBitmap,
            // so the image is upright here and needs no further rotation hint.
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            RecognizedText(
                fullText = result.text,
                blocks = result.textBlocks.map { it.text },
            )
        } catch (e: Exception) {
            throw AiException(e.toAiFailure(), e)
        }
    }

    override fun close() {
        recognizer.close()
    }
}
