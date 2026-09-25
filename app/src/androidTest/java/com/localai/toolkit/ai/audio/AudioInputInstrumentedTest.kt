package com.localai.toolkit.ai.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** Requires Android. Compilation is not evidence that the native codecs or pipe have run. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class AudioInputInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun stereoWavIsDecodedAndDownmixedTo16kMono() = runBlocking {
        val frames = 4_410
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { pcm.putShort(10_000).putShort(-10_000) }
        val file = File.createTempFile("mindkit-audio-test", ".wav", context.cacheDir)
        try {
            file.outputStream().use { stream ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(44_100 * 4)
                    .putShort(4).putShort(16).put("data".toByteArray()).putInt(pcm.capacity())
                stream.write(header.array())
                stream.write(pcm.array())
            }
            val decoded = decode(file)
            assertThat(decoded.size).isEqualTo(3_200)
            assertThat(decoded.all { it == 0.toByte() }).isTrue()
        } finally { file.delete() }
    }

    @Test fun encodedAacContainerUsesTheNativeDecoder() = runBlocking {
        val file = File.createTempFile("mindkit-audio-test", ".m4a", context.cacheDir)
        try {
            withContext(Dispatchers.IO) { makeAac(file) }
            val decoded = decode(file)
            assertThat(decoded.size).isGreaterThan(1_000)
            assertThat(decoded.size % 2).isEqualTo(0)
            assertThat(decoded.any { it != 0.toByte() }).isTrue()
        } finally { file.delete() }
    }

    @Test fun aFullUnreadNativePipeCanBeCancelled() = runBlocking {
        val pipe = AndroidPcmPipe.open()
        val readerFd = pipe.reader.fileDescriptor
        try {
            val writer = async(Dispatchers.IO) { pipe.write(ByteArray(2 * 1024 * 1024), 0, 2 * 1024 * 1024) }
            delay(100)
            assertThat(writer.isCompleted).isFalse()
            withTimeout(2_000) { writer.cancelAndJoin() }
        } finally { pipe.close() }
        assertThat(readerFd.valid()).isFalse()
    }

    private suspend fun decode(file: File): ByteArray = withTimeout(10_000) {
        val output = ByteArrayOutputStream()
        AndroidAudioDecoder(context, Dispatchers.IO).decode(Uri.fromFile(file)) { bytes ->
            assertThat(bytes.size).isAtMost(640)
            output.write(bytes)
        }
        output.toByteArray()
    }

    /** Generate a tiny fixture with the platform encoder; no downloaded/user audio is needed. */
    private fun makeAac(file: File) {
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        val encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val input = ByteBuffer.allocate(8_820).order(ByteOrder.nativeOrder()).apply {
                repeat(4_410) { putShort((12_000 * sin(2 * PI * 440 * it / 44_100)).toInt().toShort()) }
            }.array()
            var offset = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!outputEnded) {
                check(System.nanoTime() < deadline) { "Fixture encoder stalled" }
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(encoder.getInputBuffer(index)).apply { clear() }
                        val count = minOf(buffer.remaining(), input.size - offset) and -2
                        buffer.put(input, offset, count)
                        inputEnded = count == 0
                        encoder.queueInputBuffer(index, 0, count, offset.toLong() * 1_000_000 / (44_100 * 2),
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        offset += count
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(track, requireNotNull(encoder.getOutputBuffer(index)), info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(index, false) }
                }
            }
        } finally {
            try { encoder.release() } finally {
                try { if (muxerStarted) muxer.stop() } finally { muxer.release() }
            }
        }
    }
}
