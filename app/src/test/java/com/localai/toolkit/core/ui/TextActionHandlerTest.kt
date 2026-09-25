package com.localai.toolkit.core.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TextActionHandlerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messages = mutableListOf<Int>()

    @Test @Config(sdk = [32])
    fun `successful copy uses real clipboard and confirms on older Android`() {
        TextActionHandler(context, messages::add).copy("result", "日本語")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("日本語")
        assertThat(messages).containsExactly(R.string.copied_to_clipboard)
    }

    @Test fun `successful copy leaves modern Android to show the system confirmation`() {
        TextActionHandler(context, messages::add).copy("result", "text")
        assertThat(messages).isEmpty()
    }

    @Test fun `missing clipboard gives failure instead of copied feedback`() {
        val unavailable = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == CLIPBOARD_SERVICE) null else super.getSystemService(name)
        }
        TextActionHandler(unavailable, messages::add).copy("result", "text")
        assertThat(messages).containsExactly(R.string.copy_failed)
    }

    @Test fun `clipboard policy rejection gives failure without crashing`() {
        val denied = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == CLIPBOARD_SERVICE) throw SecurityException("Clipboard blocked")
                return super.getSystemService(name)
            }
        }
        TextActionHandler(denied, messages::add).copy("result", "text")
        assertThat(messages).containsExactly(R.string.copy_failed)
    }

    @Test fun `missing share activity gives failure without claiming success`() = rejectedShare(ActivityNotFoundException())

    @Test fun `share policy rejection gives failure without crashing`() = rejectedShare(SecurityException())

    @Test fun `sharing from application context adds new task and preserves plain text chooser`() {
        var launched: Intent? = null
        val applicationContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        TextActionHandler(applicationContext, messages::add).share("Visible text — 日本語")
        val chooser = launched!!
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Visible text — 日本語")
        assertThat(send.component).isNull()
        assertThat(send.`package`).isNull()
        assertThat(messages).isEmpty()
    }

    @Test fun `wrapped activity context keeps the share sheet in its existing task`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var launched: Intent? = null
            val wrapped = object : ContextWrapper(ContextWrapper(activity.get())) {
                override fun startActivity(intent: Intent) { launched = intent }
            }
            TextActionHandler(wrapped, messages::add).share("Result")
            assertThat(launched!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun rejectedShare(failure: RuntimeException) {
        val unavailable = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw failure }
        }
        TextActionHandler(unavailable, messages::add).share("Result")
        assertThat(messages).containsExactly(R.string.share_failed)
    }
}
