package com.localai.toolkit.ai.audio

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.test.runTest
import org.junit.Test

class Pcm16MonoConverterTest {
    @Test fun `16 kHz mono retains every signed sample bit for bit`() = runTest {
        val input = shorts(-32768, -1234, 0, 1234, 32767)
        assertThat(convert(input, 16_000)).isEqualTo(input)
    }

    @Test fun `stereo channels mix without overflow or one-channel bias`() = runTest {
        val input = shorts(30_000, 30_000, -30_000, -30_000, 10_000, -10_000, 0, 10_000)
        assertThat(convert(input, 16_000, channels = 2)).isEqualTo(shorts(30_000, -30_000, 0, 5_000))
    }

    @Test fun `float samples are clipped and nonfinite data is silenced`() = runTest {
        val input = ByteBuffer.allocate(7 * 4).order(ByteOrder.LITTLE_ENDIAN)
        listOf(-2f, -0.5f, 0f, 0.5f, 2f, Float.NaN, Float.POSITIVE_INFINITY).forEach(input::putFloat)
        assertThat(convert(input.array(), 16_000, encoding = PcmEncoding.FLOAT_32))
            .isEqualTo(shorts(-32768, -16384, 0, 16384, 32767, 0, 0))
    }

    @Test fun `unsigned 8 bit and packed 24 bit decode their signed endpoints`() = runTest {
        assertThat(convert(byteArrayOf(0, 128.toByte(), 255.toByte()), 16_000, encoding = PcmEncoding.UNSIGNED_8))
            .isEqualTo(shorts(-32768, 0, 32512))
        assertThat(convert(byteArrayOf(0, 0, 128.toByte(), 0, 0, 0, 255.toByte(), 255.toByte(), 127),
            16_000, encoding = PcmEncoding.SIGNED_24)).isEqualTo(shorts(-32768, 0, 32767))
    }

    @Test fun `32 bit PCM conversion retains polarity and clips maximum safely`() = runTest {
        val input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Int.MIN_VALUE).putInt(0).putInt(Int.MAX_VALUE).array()
        assertThat(convert(input, 16_000, encoding = PcmEncoding.SIGNED_32)).isEqualTo(shorts(-32768, 0, 32767))
    }

    @Test fun `split bytes frames and resampling blocks produce identical output`() = runTest {
        val input = sine(44_100, 1_000, 4_410)
        val whole = convert(input, 44_100)
        val split = convert(input, 44_100, chunkSize = 13)
        assertThat(split).isEqualTo(whole)
        assertThat(whole.size).isEqualTo(3_200)
    }

    @Test fun `upsampling preserves duration and constant amplitude`() = runTest {
        val input = shorts(*IntArray(801) { 8_000 })
        val output = samples(convert(input, 8_000))
        assertThat(output.size).isEqualTo(1_602)
        assertThat(output.all { abs(it - 8_000) <= 1 }).isTrue()
    }

    @Test fun `downsampling suppresses frequencies that would alias into speech`() = runTest {
        val audible = samples(convert(sine(48_000, 1_000, 48_000), 48_000)).drop(100).dropLast(100)
        val aboveNyquist = samples(convert(sine(48_000, 12_000, 48_000), 48_000)).drop(100).dropLast(100)
        fun rms(values: List<Int>) = sqrt(values.sumOf { it.toDouble() * it } / values.size)
        assertThat(rms(audible)).isGreaterThan(12_000.0)
        assertThat(rms(aboveNyquist)).isLessThan(rms(audible) * 0.02)
    }

    @Test fun `very short input flushes with exact resampled duration`() = runTest {
        assertThat(samples(convert(shorts(12_345), 48_000))).containsExactly(12_345)
        assertThat(samples(convert(shorts(12_345), 8_000))).containsExactly(12_345, 12_345)
    }

    @Test fun `incomplete last frame is rejected rather than silently truncated`() = runTest {
        val converter = Pcm16MonoConverter(16_000, 2, PcmEncoding.SIGNED_16)
        converter.accept(ByteBuffer.wrap(byteArrayOf(1, 2, 3))) {}
        assertThat(runCatching { converter.finish {} }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun `invalid format metadata is rejected before allocation`() {
        assertThat(runCatching { Pcm16MonoConverter(0, 2, PcmEncoding.SIGNED_16) }.isFailure).isTrue()
        assertThat(runCatching { Pcm16MonoConverter(16_000, 0, PcmEncoding.SIGNED_16) }.isFailure).isTrue()
        assertThat(runCatching { Pcm16MonoConverter(16_000, Int.MAX_VALUE, PcmEncoding.SIGNED_16) }.isFailure).isTrue()
    }

    private suspend fun convert(input: ByteArray, rate: Int, channels: Int = 1,
        encoding: PcmEncoding = PcmEncoding.SIGNED_16, chunkSize: Int = 4096): ByteArray {
        val converter = Pcm16MonoConverter(rate, channels, encoding)
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < input.size) {
            val count = minOf(chunkSize, input.size - offset)
            converter.accept(ByteBuffer.wrap(input, offset, count)) { bytes ->
                assertThat(bytes.size).isAtMost(640)
                output.write(bytes)
            }
            offset += count
        }
        converter.finish { bytes -> output.write(bytes) }
        return output.toByteArray()
    }

    private fun shorts(vararg samples: Int): ByteArray = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) } }.array()

    private fun samples(bytes: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 2) { buffer.short.toInt() }
    }

    private fun sine(rate: Int, hz: Int, count: Int) = shorts(*IntArray(count) {
        (20_000 * sin(2 * PI * hz * it / rate)).toInt()
    })
}
