package com.localai.toolkit.feature.share

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ShareRouterViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = IncomingShareStore()
    private val handoff = ToolHandoff()
    private fun model() = main.own(ShareRouterViewModel(store, handoff))

    @Test fun `a second share updates the same router and routes only the new content`() = runTest {
        store.set(SharedContent.Text("first"))
        val vm = model()
        runCurrent()
        val uri = Uri.parse("content://test/photo")
        store.set(SharedContent.Image(uri))
        runCurrent()
        assertThat(vm.uiState.value.actions).containsExactly(ToolId.OCR, ToolId.IMAGE)
        assertThat(vm.route(ToolId.OCR)).isTrue()
        assertThat(handoff.consume(ToolId.OCR)?.imageUri).isEqualTo(uri)
    }

    @Test fun `a routed share cannot be replayed by a second tap`() = runTest {
        store.set(SharedContent.Text("once"))
        val vm = model()
        assertThat(vm.route(ToolId.SUMMARIZE)).isTrue()
        assertThat(handoff.consume(ToolId.SUMMARIZE)?.text).isEqualTo("once")
        assertThat(vm.route(ToolId.SUMMARIZE)).isFalse()
        assertThat(handoff.consume(ToolId.SUMMARIZE)).isNull()
    }

    @Test fun `an incompatible stale action does not consume a newly arrived share`() = runTest {
        store.set(SharedContent.Text("old text"))
        val vm = model()
        store.set(SharedContent.Audio(Uri.parse("content://test/audio")))
        assertThat(vm.route(ToolId.SUMMARIZE)).isFalse()
        assertThat(store.pending.value).isInstanceOf(SharedContent.Audio::class.java)
        assertThat(vm.route(ToolId.TRANSCRIBE)).isTrue()
        assertThat(handoff.consume(ToolId.TRANSCRIBE)?.audioUri).isNotNull()
    }

    @Test fun `cancel clears the share so old router state cannot route it`() = runTest {
        store.set(SharedContent.Text("cancel this"))
        val vm = model()
        vm.cancel()
        runCurrent()
        assertThat(vm.uiState.value.actions).isEmpty()
        assertThat(vm.route(ToolId.ASK)).isFalse()
        assertThat(store.pending.value).isNull()
    }
}
