package com.localai.toolkit.ai.audio

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PcmAudioInputTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uri = Uri.parse("content://test/encoded-audio")

    @Test fun `decoded PCM is paced and both pipe ends close after recognition`() = runTest {
        val pipe = pipe().apply { clock = { testScheduler.currentTime } }
        val data = ByteArray(1_920) { it.toByte() }
        var opened: Uri? = null
        val decoder = decoder { selected, emit -> opened = selected; emit(data) }
        val input = PcmAudioInput(decoder, StandardTestDispatcher(testScheduler), { pipe }, { testScheduler.currentTime * 1_000_000 })
        val result = input.recognize(uri) { descriptor -> flow {
            assertThat(descriptor).isSameInstanceAs(pipe.reader)
            pipe.finished.await()
            emit("transcript")
        } }.single()
        assertThat(result).isEqualTo("transcript")
        assertThat(opened).isEqualTo(uri)
        assertThat(pipe.bytes.toByteArray()).isEqualTo(data)
        assertThat(pipe.writeTimes).containsExactly(0L, 20L, 40L).inOrder()
        assertThat(pipe.closed).isTrue()
    }

    @Test fun `a delayed pipe write cannot cause a catch-up burst into recognition`() = runTest {
        val pipe = pipe().apply { clock = { testScheduler.currentTime }; firstWriteDelay = 1_000 }
        val input = PcmAudioInput(decoder { _, emit -> emit(ByteArray(1_920)) },
            StandardTestDispatcher(testScheduler), { pipe }, { testScheduler.currentTime * 1_000_000 })
        input.recognize(uri) { flow { pipe.finished.await(); emit(Unit) } }.single()
        assertThat(pipe.writeTimes).containsExactly(0L, 1_020L, 1_040L).inOrder()
    }

    @Test fun `decode failure cancels recognition and reports invalid audio`() = runTest {
        val pipe = pipe()
        var recognitionClosed = false
        val input = PcmAudioInput(decoder { _, _ -> throw IOException("bad container") }, StandardTestDispatcher(testScheduler), { pipe })
        val error = runCatching { input.recognize<String>(uri) { flow {
            try { awaitCancellation() } finally { recognitionClosed = true }
        } }.toList() }.exceptionOrNull()
        assertThat((error as AiException).failure).isInstanceOf(AiFailure.InvalidAudio::class.java)
        assertThat(recognitionClosed).isTrue()
        assertThat(pipe.finished.isCompleted).isTrue()
        assertThat(pipe.closed).isTrue()
    }

    @Test fun `recognition failure stops decoding and retains its specific failure`() = runTest {
        val pipe = pipe()
        val started = CompletableDeferred<Unit>()
        var decoderClosed = false
        val input = PcmAudioInput(decoder { _, _ ->
            try { started.complete(Unit); awaitCancellation() } finally { decoderClosed = true }
        }, StandardTestDispatcher(testScheduler), { pipe })
        val error = runCatching { input.recognize<String>(uri) { flow {
            started.await()
            throw AiException(AiFailure.Busy())
        } }.toList() }.exceptionOrNull()
        assertThat((error as AiException).failure).isInstanceOf(AiFailure.Busy::class.java)
        assertThat(decoderClosed).isTrue()
        assertThat(pipe.closed).isTrue()
    }

    @Test fun `cancel while sink is full closes decoding and pipe without an error result`() = runTest {
        val pipe = pipe().apply { blockWrites = true }
        var decoderClosed = false
        val input = PcmAudioInput(decoder { _, emit ->
            try { emit(ByteArray(640)) } finally { decoderClosed = true }
        }, StandardTestDispatcher(testScheduler), { pipe })
        val work = async { input.recognize<String>(uri) { flow { awaitCancellation() } }.toList() }
        runCurrent()
        assertThat(pipe.writeStarted.isCompleted).isTrue()
        work.cancel()
        runCurrent()
        assertThat(work.isCancelled).isTrue()
        assertThat(decoderClosed).isTrue()
        assertThat(pipe.closed).isTrue()
    }

    @Test fun `empty decoded file is rejected and all resources close`() = runTest {
        val pipe = pipe()
        val input = PcmAudioInput(decoder { _, _ -> }, StandardTestDispatcher(testScheduler), { pipe })
        val error = runCatching { input.recognize<String>(uri) { flow { awaitCancellation() } }.toList() }.exceptionOrNull()
        assertThat((error as AiException).failure).isInstanceOf(AiFailure.InvalidAudio::class.java)
        assertThat(pipe.closed).isTrue()
    }

    @Test fun `odd decoded PCM chunk is rejected before it reaches speech`() = runTest {
        val pipe = pipe()
        val input = PcmAudioInput(decoder { _, emit -> emit(byteArrayOf(1)) }, StandardTestDispatcher(testScheduler), { pipe })
        val error = runCatching { input.recognize<String>(uri) { flow { awaitCancellation() } }.toList() }.exceptionOrNull()
        assertThat((error as AiException).failure).isInstanceOf(AiFailure.InvalidAudio::class.java)
        assertThat(pipe.bytes.size()).isEqualTo(0)
        assertThat(pipe.closed).isTrue()
    }

    private fun decoder(block: suspend (Uri, suspend (ByteArray) -> Unit) -> Unit) = object : AudioDecoder {
        override suspend fun decode(uri: Uri, emit: suspend (ByteArray) -> Unit) = block(uri, emit)
    }

    private fun pipe() = TestPipe(ParcelFileDescriptor.open(temporary.newFile(), ParcelFileDescriptor.MODE_READ_ONLY))

    private class TestPipe(override val reader: ParcelFileDescriptor) : PcmPipe {
        val bytes = ByteArrayOutputStream()
        val finished = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val writeTimes = mutableListOf<Long>()
        var blockWrites = false
        var closed = false
        var firstWriteDelay = 0L
        var clock: () -> Long = { 0L }
        override suspend fun write(bytes: ByteArray, offset: Int, count: Int) {
            writeStarted.complete(Unit)
            if (blockWrites) awaitCancellation()
            writeTimes += clock()
            if (firstWriteDelay > 0) {
                val wait = firstWriteDelay
                firstWriteDelay = 0
                delay(wait)
            }
            this.bytes.write(bytes, offset, count)
        }
        override fun finishWriting() { finished.complete(Unit) }
        override fun close() { closed = true; reader.close() }
    }
}
