package com.localai.toolkit.ai.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class PcmEncoding(val bytes: Int) { UNSIGNED_8(1), SIGNED_16(2), SIGNED_24(3), SIGNED_32(4), FLOAT_32(4) }

/** Chunk-independent, bounded-memory PCM conversion. Output is little-endian 16 kHz mono. */
internal class Pcm16MonoConverter(
    private val sampleRate: Int,
    private val channels: Int,
    private val encoding: PcmEncoding,
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
) {
    init {
        require(sampleRate in 1..384_000) { "Invalid PCM sample rate: $sampleRate" }
        require(channels in 1..32) { "Invalid PCM channel count: $channels" }
    }

    private val frame = ByteBuffer.allocate(channels * encoding.bytes).order(byteOrder)
    private val halfWidth = ceil(16.0 * max(1.0, sampleRate.toDouble() / OUTPUT_RATE)).toInt()
    private val samples = DoubleArray(halfWidth * 2 + 4)
    private var inputFrames = 0L
    private var outputFrames = 0L
    private var first = 0.0
    private var last = 0.0
    private var finished = false
    private val output = ByteArray(640) // 20 ms; never accumulates a whole file.
    private var outputSize = 0

    // A windowed-sinc low-pass removes frequencies above the new Nyquist limit.
    // Merely skipping/interpolating samples would alias high frequencies into speech.
    private val kernels = if (sampleRate == OUTPUT_RATE) emptyArray() else Array(PHASES) { phase ->
        val fraction = phase.toDouble() / PHASES
        val cutoff = min(1.0, OUTPUT_RATE.toDouble() / sampleRate) * 0.9
        val weights = DoubleArray(halfWidth * 2) { tap ->
            val distance = tap - halfWidth + 1 - fraction
            val x = PI * cutoff * distance
            val sinc = if (kotlin.math.abs(x) < 1e-12) 1.0 else sin(x) / x
            cutoff * sinc * (0.5 + 0.5 * cos(PI * distance / halfWidth))
        }
        val sum = weights.sum()
        DoubleArray(weights.size) { weights[it] / sum }
    }

    suspend fun accept(input: ByteBuffer, emit: suspend (ByteArray) -> Unit) {
        check(!finished) { "PCM stream is already finished" }
        while (input.hasRemaining()) {
            val count = min(input.remaining(), frame.remaining())
            repeat(count) { frame.put(input.get()) }
            if (frame.hasRemaining()) continue
            frame.flip()
            var mono = 0.0
            repeat(channels) { mono += readSample() }
            frame.clear()
            mono /= channels
            if (inputFrames == 0L) first = mono
            last = mono
            samples[(inputFrames % samples.size).toInt()] = mono
            inputFrames++
            if (sampleRate == OUTPUT_RATE) {
                emitSample(mono, emit)
                outputFrames++
            } else {
                emitReady(endOfInput = false, emit)
            }
            if (inputFrames % 1024 == 0L) currentCoroutineContext().ensureActive()
        }
    }

    suspend fun finish(emit: suspend (ByteArray) -> Unit) {
        check(!finished) { "PCM stream is already finished" }
        finished = true
        require(frame.position() == 0) { "Audio ends inside a PCM frame" }
        if (sampleRate != OUTPUT_RATE) emitReady(endOfInput = true, emit)
        if (outputSize > 0) emit(output.copyOf(outputSize))
        outputSize = 0
    }

    private fun readSample(): Double {
        val value = when (encoding) {
            PcmEncoding.UNSIGNED_8 -> ((frame.get().toInt() and 0xff) - 128) / 128.0
            PcmEncoding.SIGNED_16 -> frame.short / 32768.0
            PcmEncoding.SIGNED_24 -> {
                val a = frame.get().toInt() and 0xff
                val b = frame.get().toInt() and 0xff
                val c = frame.get().toInt() and 0xff
                val packed = if (frame.order() == ByteOrder.LITTLE_ENDIAN) a or (b shl 8) or (c shl 16)
                    else c or (b shl 8) or (a shl 16)
                ((packed shl 8) shr 8) / 8388608.0
            }
            PcmEncoding.SIGNED_32 -> frame.int / 2147483648.0
            PcmEncoding.FLOAT_32 -> frame.float.toDouble()
        }
        return if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
    }

    private suspend fun emitReady(endOfInput: Boolean, emit: suspend (ByteArray) -> Unit) {
        while (outputFrames * sampleRate < inputFrames * OUTPUT_RATE) {
            val position = outputFrames * sampleRate
            val center = position / OUTPUT_RATE
            if (!endOfInput && center + halfWidth >= inputFrames) return
            val phase = ((position % OUTPUT_RATE) * PHASES / OUTPUT_RATE).toInt()
            val weights = kernels[phase]
            var value = 0.0
            for (tap in weights.indices) {
                val index = center + tap - halfWidth + 1
                val sample = when {
                    index < 0 -> first
                    index >= inputFrames -> last
                    else -> samples[(index % samples.size).toInt()]
                }
                value += sample * weights[tap]
            }
            emitSample(value, emit)
            outputFrames++
        }
    }

    private suspend fun emitSample(value: Double, emit: suspend (ByteArray) -> Unit) {
        val sample = (value * 32768).roundToInt().coerceIn(-32768, 32767)
        output[outputSize++] = sample.toByte()
        output[outputSize++] = (sample shr 8).toByte()
        if (outputSize == output.size) {
            emit(output.copyOf())
            outputSize = 0
        }
    }

    companion object {
        const val OUTPUT_RATE = 16_000
        private const val PHASES = 256
    }
}
