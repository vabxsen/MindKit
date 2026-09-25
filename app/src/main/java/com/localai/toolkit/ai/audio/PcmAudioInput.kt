package com.localai.toolkit.ai.audio

import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.localai.toolkit.di.IoDispatcher
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface PcmPipe : Closeable {
    val reader: ParcelFileDescriptor
    suspend fun write(bytes: ByteArray, offset: Int, count: Int)
    fun finishWriting()
}

/** Owns the decoder and both ends of a pipe for exactly one recognition collection. */
class PcmAudioInput internal constructor(
    private val decoder: AudioDecoder,
    private val ioDispatcher: CoroutineDispatcher,
    private val pipeFactory: () -> PcmPipe,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Inject constructor(decoder: AudioDecoder, @IoDispatcher ioDispatcher: CoroutineDispatcher) :
        this(decoder, ioDispatcher, { AndroidPcmPipe.open() })

    fun <T> recognize(uri: Uri, recognize: (ParcelFileDescriptor) -> Flow<T>): Flow<T> = channelFlow {
        val pipe = pipeFactory()
        try {
            val producer = launch(ioDispatcher) {
                var byteCount = 0L
                var nextPacketNanos: Long? = null
                try {
                    decoder.decode(uri) { bytes ->
                        require(bytes.size % 2 == 0) { "PCM output must contain complete 16-bit samples" }
                        var offset = 0
                        while (offset < bytes.size) {
                            currentCoroutineContext().ensureActive()
                            // STREAMING must not flood AICore faster than live audio.
                            val due = nextPacketNanos ?: nanoTime()
                            val remaining = due - nanoTime()
                            if (remaining > 0) delay((remaining + 999_999) / 1_000_000)
                            val count = minOf(PACKET_BYTES, bytes.size - offset)
                            pipe.write(bytes, offset, count)
                            // A slow decoder/consumer must not be followed by a catch-up burst.
                            nextPacketNanos = maxOf(due, nanoTime()) + count * 1_000_000_000L / BYTES_PER_SECOND
                            offset += count
                            byteCount += count
                        }
                    }
                    require(byteCount > 0) { "The audio file contains no decodable samples" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw AiException(AiFailure.InvalidAudio(error.message), error)
                } finally {
                    pipe.finishWriting()
                }
            }
            try {
                recognize(pipe.reader).collect { send(it) }
            } finally {
                producer.cancel()
                // Writes are non-blocking, so cancellation can finish before descriptors close.
                withContext(NonCancellable) { producer.join() }
            }
        } finally {
            // Closing the reader also releases the input if recognition fails before decoding ends.
            pipe.close()
        }
    }.flowOn(ioDispatcher).catch { error ->
        if (error is CancellationException || error is AiException) throw error
        throw AiException(AiFailure.Unknown(error.message), error)
    }

    private companion object {
        const val BYTES_PER_SECOND = 16_000 * 2
        const val PACKET_BYTES = 640 // 20 ms of mono PCM-16.
    }
}

/** Non-blocking writes keep a full/unread pipe cancellable without closing a live fd from another thread. */
internal class AndroidPcmPipe private constructor(
    override val reader: ParcelFileDescriptor,
    private val writer: ParcelFileDescriptor,
) : PcmPipe {
    private val writerClosed = AtomicBoolean()
    private val readerClosed = AtomicBoolean()

    override suspend fun write(bytes: ByteArray, offset: Int, count: Int) {
        var written = 0
        while (written < count) {
            currentCoroutineContext().ensureActive()
            try {
                val size = Os.write(writer.fileDescriptor, bytes, offset + written, count - written)
                if (size <= 0) throw IOException("Audio pipe accepted no data")
                written += size
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.EAGAIN -> delay(10)
                    OsConstants.EINTR -> Unit
                    else -> throw IOException("Unable to stream decoded audio", error)
                }
            }
        }
    }

    override fun finishWriting() {
        if (writerClosed.compareAndSet(false, true)) runCatching { writer.close() }
    }

    override fun close() {
        finishWriting()
        if (readerClosed.compareAndSet(false, true)) runCatching { reader.close() }
    }

    companion object {
        fun open(): AndroidPcmPipe {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw AiException(AiFailure.Unsupported("File transcription requires Android 12 or newer"))
            }
            val descriptors = ParcelFileDescriptor.createPipe()
            try {
                val fd = descriptors[1].fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                return AndroidPcmPipe(descriptors[0], descriptors[1])
            } catch (error: Exception) {
                descriptors.forEach { runCatching { it.close() } }
                throw error
            }
        }
    }
}
