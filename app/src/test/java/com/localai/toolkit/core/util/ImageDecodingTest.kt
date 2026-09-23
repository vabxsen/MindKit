package com.localai.toolkit.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Downsampling maths.
 *
 * This decides whether a 48 MP photo becomes a manageable bitmap or an
 * OutOfMemoryError, so the boundaries are pinned down rather than eyeballed.
 */
class ImageDecodingTest {

    @Test
    fun `an image already within the target is not downsampled`() {
        assertThat(calculateInSampleSize(1600, 1200, maxDimension = 2048)).isEqualTo(1)
    }

    @Test
    fun `sample size is chosen from the longest edge regardless of orientation`() {
        val landscape = calculateInSampleSize(4000, 3000, maxDimension = 1024)
        val portrait = calculateInSampleSize(3000, 4000, maxDimension = 1024)

        assertThat(landscape).isEqualTo(portrait)
    }

    @Test
    fun `a large photo is reduced to just above the target`() {
        // 4000 / 2 = 2000, which is below 2048, so halving once more would undershoot.
        assertThat(calculateInSampleSize(4000, 3000, maxDimension = 2048)).isEqualTo(1)
        // 8000 / 4 = 2000; sampling by 2 leaves 4000, still >= 2 * 2048.
        assertThat(calculateInSampleSize(8000, 6000, maxDimension = 2048)).isEqualTo(2)
    }

    @Test
    fun `sample size is always a power of two`() {
        val sizes = listOf(1000, 2500, 4000, 6000, 9000, 12000, 20000).map {
            calculateInSampleSize(it, it, maxDimension = 1024)
        }

        // BitmapFactory rounds to powers of two anyway; returning one directly keeps the
        // resulting dimensions predictable.
        sizes.forEach { size ->
            assertThat(size and (size - 1)).isEqualTo(0)
        }
    }

    @Test
    fun `the decoded result stays at or above the target dimension`() {
        val widths = listOf(1024, 2049, 4097, 8193, 16385)
        widths.forEach { width ->
            val sample = calculateInSampleSize(width, width, maxDimension = 1024)
            assertThat(width / sample).isAtLeast(1024)
        }
    }
}
