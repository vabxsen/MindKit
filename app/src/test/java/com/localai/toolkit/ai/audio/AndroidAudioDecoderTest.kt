package com.localai.toolkit.ai.audio

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource

/** Tests adapter control flow with simulated platform extraction/decoding, not real codecs. */
@RunWith(RobolectricTestRunner::class)
class AndroidAudioDecoderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://test/audio")
    private val source = DataSource.toDataSource(context, uri, null)

    @Test fun `extractor skips nonaudio tracks and normalizes raw audio`() = runTest {
        ShadowMediaExtractor.addTrack(source, MediaFormat.createVideoFormat("video/avc", 8, 8), byteArrayOf(1))
        val pcm = ByteBuffer.allocate(32_000).order(ByteOrder.nativeOrder())
        repeat(8_000) { pcm.putShort(8_000).putShort(-8_000) }
        ShadowMediaExtractor.addTrack(source, MediaFormat.createAudioFormat("audio/raw", 8_000, 2), pcm.array())
        val bytes = ByteArrayOutputStream()
        AndroidAudioDecoder(context, StandardTestDispatcher(testScheduler)).decode(uri) { bytes.write(it) }
        assertThat(bytes.size()).isEqualTo(32_000)
        assertThat(bytes.toByteArray().all { it == 0.toByte() }).isTrue()
    }

    @Test fun `non audio content is rejected instead of sent to speech`() = runTest {
        ShadowMediaExtractor.addTrack(source, MediaFormat.createVideoFormat("video/avc", 8, 8), byteArrayOf(1))
        var emissions = 0
        val error = runCatching {
            AndroidAudioDecoder(context, StandardTestDispatcher(testScheduler)).decode(uri) { emissions++ }
        }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(emissions).isEqualTo(0)
    }

    @Test fun `compressed input goes through the codec rather than forwarding original bytes`() = runTest {
        val encoded = byteArrayOf(1, 2, 3, 4)
        val expected = byteArrayOf(0x34, 0x12)
        var codecCalls = 0
        ShadowMediaExtractor.addTrack(source, MediaFormat.createAudioFormat("audio/mpeg", 16_000, 1), encoded)
        ShadowMediaCodec.addDecoder("audio/mpeg", ShadowMediaCodec.CodecConfig(4096, 4096) { input, output ->
            if (input.hasRemaining()) {
                codecCalls++
                input.position(input.limit())
                output.put(expected)
            }
        })
        val bytes = ByteArrayOutputStream()
        AndroidAudioDecoder(context, StandardTestDispatcher(testScheduler)).decode(uri) { bytes.write(it) }
        assertThat(codecCalls).isEqualTo(1)
        assertThat(bytes.toByteArray()).isEqualTo(expected)
    }
}
