package com.localai.toolkit.feature.share

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Parsing of incoming share intents.
 *
 * This is the app's only inbound data path from other apps, so the cases that must not
 * open a broken screen - wrong action, unhandled MIME type, missing extra - are covered
 * alongside the happy paths.
 */
@RunWith(RobolectricTestRunner::class)
class IncomingShareTest {

    @Test
    fun `plain text is parsed as text`() {
        val intent = sendIntent("text/plain") {
            putExtra(Intent.EXTRA_TEXT, "hello world")
        }

        val content = intent.toSharedContent()

        assertThat(content).isInstanceOf(SharedContent.Text::class.java)
        assertThat((content as SharedContent.Text).value).isEqualTo("hello world")
    }

    @Test
    fun `an html share is still treated as text`() {
        // Some apps send text/html; the body is still text the tools can work on.
        val intent = sendIntent("text/html") { putExtra(Intent.EXTRA_TEXT, "<p>hi</p>") }

        assertThat(intent.toSharedContent()).isInstanceOf(SharedContent.Text::class.java)
    }

    @Test
    fun `a subject with no body is used rather than dropped`() {
        val intent = sendIntent("text/plain") {
            putExtra(Intent.EXTRA_SUBJECT, "Meeting notes")
        }

        val content = intent.toSharedContent()

        assertThat((content as SharedContent.Text).value).isEqualTo("Meeting notes")
    }

    @Test
    fun `blank text is rejected so the action screen is never empty`() {
        val intent = sendIntent("text/plain") { putExtra(Intent.EXTRA_TEXT, "   ") }

        assertThat(intent.toSharedContent()).isNull()
    }

    @Test
    fun `an image share carries its uri`() {
        val uri = Uri.parse("content://media/external/images/1")
        val intent = sendIntent("image/jpeg") { putExtra(Intent.EXTRA_STREAM, uri) }

        val content = intent.toSharedContent()

        assertThat(content).isInstanceOf(SharedContent.Image::class.java)
        assertThat((content as SharedContent.Image).uri).isEqualTo(uri)
    }

    @Test
    fun `an audio share carries its uri`() {
        val uri = Uri.parse("content://media/external/audio/7")
        val intent = sendIntent("audio/mpeg") { putExtra(Intent.EXTRA_STREAM, uri) }

        val content = intent.toSharedContent()

        assertThat((content as SharedContent.Audio).uri).isEqualTo(uri)
    }

    @Test
    fun `an image share with no stream is rejected`() {
        assertThat(sendIntent("image/png") {}.toSharedContent()).isNull()
    }

    @Test
    fun `an unhandled mime type is rejected so the app opens Home instead`() {
        val intent = sendIntent("application/pdf") {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/1"))
        }

        assertThat(intent.toSharedContent()).isNull()
    }

    @Test
    fun `a non-send action is ignored`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "hello")
        }

        assertThat(intent.toSharedContent()).isNull()
    }

    @Test
    fun `an intent with no type at all is ignored`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "hello")
        }

        assertThat(intent.toSharedContent()).isNull()
    }

    @Test
    fun `the store hands the payload over exactly once`() {
        val store = IncomingShareStore()
        store.set(SharedContent.Text("once"))

        assertThat(store.consume()).isNotNull()
        // A second read must be empty, so returning to the screen cannot replay a share
        // the user already acted on.
        assertThat(store.consume()).isNull()
    }

    private fun sendIntent(mimeType: String, block: Intent.() -> Unit): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            block()
        }
}
