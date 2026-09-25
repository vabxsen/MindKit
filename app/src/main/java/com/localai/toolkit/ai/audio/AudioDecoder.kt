package com.localai.toolkit.ai.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.localai.toolkit.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Emits raw little-endian PCM-16/16 kHz/mono in bounded chunks. No cache files. */
interface AudioDecoder {
    suspend fun decode(uri: Uri, emit: suspend (ByteArray) -> Unit)
}

class AndroidAudioDecoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioDecoder {
    override suspend fun decode(uri: Uri, emit: suspend (ByteArray) -> Unit) = withContext(ioDispatcher) {
        val extractor = MediaExtractor()
        try {
            // Use the granted content URI; do not copy the original or decode into a disk cache.
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The selected file has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
                decodeRaw(extractor, format, emit)
            } else {
                decodeCompressed(extractor, format, emit)
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeRaw(extractor: MediaExtractor, format: MediaFormat, emit: suspend (ByteArray) -> Unit) {
        val converter = format.converter()
        var buffer = ByteBuffer.allocate(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val size = extractor.sampleSize
                require(size <= MAX_PACKET_BYTES) { "Audio packet is too large" }
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size.toInt())
            }
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            converter.accept(buffer, emit)
            if (!extractor.advance()) break
        }
        converter.finish(emit)
    }

    private suspend fun decodeCompressed(extractor: MediaExtractor, format: MediaFormat, emit: suspend (ByteArray) -> Unit) {
        val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var inputEnded = false
            var outputEnded = false
            var converter: Pcm16MonoConverter? = null
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() - lastProgress < CODEC_STALL_NANOS) { "Audio decoder stopped making progress" }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_WAIT_US)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_WAIT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        converter?.finish(emit)
                        converter = codec.outputFormat.converter()
                        lastProgress = System.nanoTime()
                    }
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val output = requireNotNull(codec.getOutputBuffer(outputIndex))
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val active = converter ?: codec.getOutputFormat(outputIndex).converter().also { converter = it }
                                active.accept(output, emit)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        lastProgress = System.nanoTime()
                    }
                }
            }
            converter?.finish(emit)
        } finally {
            // release is valid even when configure/start failed; no leaked native codec.
            codec.release()
        }
    }

    private fun MediaFormat.converter(): Pcm16MonoConverter {
        val encoding = if (containsKey(MediaFormat.KEY_PCM_ENCODING)) getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        val pcm = when {
            encoding == AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.SIGNED_16
            encoding == AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT_32
            encoding == AudioFormat.ENCODING_PCM_8BIT -> PcmEncoding.UNSIGNED_8
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.SIGNED_24
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && encoding == AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.SIGNED_32
            else -> error("Unsupported decoded PCM encoding: $encoding")
        }
        return Pcm16MonoConverter(getInteger(MediaFormat.KEY_SAMPLE_RATE), getInteger(MediaFormat.KEY_CHANNEL_COUNT), pcm,
            ByteOrder.nativeOrder())
    }

    private companion object {
        const val CODEC_WAIT_US = 10_000L
        const val CODEC_STALL_NANOS = 15_000_000_000L
        const val MAX_PACKET_BYTES = 4 * 1024 * 1024L
    }
}
