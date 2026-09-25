package com.localai.toolkit.feature.share

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedContentStateTest {
    @Test fun `latest text survives a parcel round trip independently of the original intent`() {
        val store = IncomingShareStore()
        store.set(SharedContent.Text("first share"))
        store.set(SharedContent.Text("latest share"))
        val state = roundTrip(store.pending.value!!.toSavedState())
        store.clear()
        assertThat(state.toSharedContent()).isEqualTo(SharedContent.Text("latest share"))
    }

    @Test fun `image and audio uri types survive parceling without embedding file bytes`() {
        listOf(SharedContent.Image(Uri.parse("content://test/image/1")),
            SharedContent.Audio(Uri.parse("content://test/audio/2"))).forEach { content ->
            val state = roundTrip(content.toSavedState())
            assertThat(state.keySet()).containsExactly("kind", "value")
            assertThat(state.toSharedContent()).isEqualTo(content)
        }
    }

    @Test fun `missing blank and unknown saved payloads are ignored`() {
        assertThat(Bundle().toSharedContent()).isNull()
        assertThat(Bundle().apply { putString("kind", "text"); putString("value", " ") }.toSharedContent()).isNull()
        assertThat(Bundle().apply { putString("kind", "unknown"); putString("value", "value") }.toSharedContent()).isNull()
    }

    private fun roundTrip(state: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            checkNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
    }
}
