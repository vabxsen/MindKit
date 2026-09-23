package com.localai.toolkit.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/**
 * Loads a bitmap from [uri], downsampled so it never costs more memory than the work needs.
 *
 * A modern phone photo is easily 4000x3000 - around 48 MB as an ARGB_8888 bitmap. Text
 * recognition and image description do not benefit from that resolution, and decoding it
 * in full is the fastest way to an OutOfMemoryError on a mid-range device. So the image
 * is measured first, then decoded with an inSampleSize that lands just above
 * [maxDimension], and finally rotated according to its EXIF orientation - without which
 * text in a portrait photo arrives sideways and OCR quality collapses.
 *
 * @param maxDimension target upper bound for the longest edge.
 * @return the decoded bitmap, or null when the URI could not be read or decoded.
 */
fun Context.decodeDownsampledBitmap(uri: Uri, maxDimension: Int = 2048): Bitmap? {
    val (width, height) = readImageBounds(uri) ?: return null
    if (width <= 0 || height <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(width, height, maxDimension)
        // OCR and the GenAI image APIs both want a straightforward 8888 bitmap.
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decoded = try {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        // The grant behind a shared URI can expire before the user acts on it.
        null
    } ?: return null

    return applyExifRotation(uri, decoded)
}

private fun Context.readImageBounds(uri: Uri): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) null else bounds.outWidth to bounds.outHeight
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

/**
 * Largest power of two that keeps the longest edge at or above [maxDimension].
 *
 * Powers of two are what BitmapFactory actually honours, and staying above the target
 * means the subsequent rotation never has to upscale.
 */
internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    var longestEdge = maxOf(width, height)
    while (longestEdge / 2 >= maxDimension) {
        longestEdge /= 2
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * Rotates and flips [bitmap] to match the EXIF orientation recorded in [uri].
 *
 * Returns the original bitmap unchanged when no transform is needed, so the common case
 * costs nothing.
 */
private fun Context.applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    } catch (e: SecurityException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }

    return try {
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        rotated
    } catch (e: OutOfMemoryError) {
        // Better an unrotated result than a crash; recognition will simply do worse.
        bitmap
    }
}
