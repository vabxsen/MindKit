package com.localai.toolkit.ai.gemini

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformSpeechInputTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `start failure destroys recognizer and permits a fresh session`() = runTest {
        val failed = FakeRecognizer().apply { startFails = true }
        val next = FakeRecognizer()
        val recognizers = ArrayDeque(listOf(failed, next))
        val input = PlatformSpeechInput({ true }, { recognizers.removeFirst() })
        val failure = async { runCatching { input.recognize().toList() }.exceptionOrNull() }
        runCurrent()
        assertThat((failure.await() as AiException).failure).isInstanceOf(AiFailure.Unknown::class.java)
        assertThat(failed.destroyCalls).isEqualTo(1)
        val retry = async { input.recognize().toList() }
        runCurrent()
        assertThat(next.intent).isNotNull()
        retry.cancel()
        runCurrent()
        assertThat(next.destroyCalls).isEqualTo(1)
    }

    @Test fun `listener registration failure still destroys created recognizer`() = runTest {
        val recognizer = FakeRecognizer().apply { listenerFails = true }
        val input = PlatformSpeechInput({ true }, { recognizer })
        val failure = async { runCatching { input.recognize().toList() }.exceptionOrNull() }
        runCurrent()
        assertThat(failure.await()).isInstanceOf(AiException::class.java)
        assertThat(recognizer.destroyCalls).isEqualTo(1)
        assertThat(recognizer.intent).isNull()
    }

    @Test fun `cancel failure does not skip destroy or replace coroutine cancellation`() = runTest {
        val recognizer = FakeRecognizer().apply { cancelFails = true }
        val input = PlatformSpeechInput({ true }, { recognizer })
        val work = async { input.recognize().toList() }
        runCurrent()
        work.cancel()
        runCurrent()
        assertThat(work.isCancelled).isTrue()
        assertThat(recognizer.cancelCalls).isEqualTo(1)
        assertThat(recognizer.destroyCalls).isEqualTo(1)
    }

    @Test fun `partial and final callbacks complete stream and release once`() = runTest {
        val recognizer = FakeRecognizer()
        val input = PlatformSpeechInput({ true }, { recognizer })
        val work = async { input.recognize().toList() }
        runCurrent()
        recognizer.callbacks.onPartialResults(text("hel"))
        recognizer.callbacks.onResults(text("hello"))
        runCurrent()
        assertThat(work.await()).containsExactly(
            TranscriptChunk.Partial("hel"), TranscriptChunk.Final("hello"), TranscriptChunk.Completed,
        ).inOrder()
        input.release()
        assertThat(recognizer.destroyCalls).isEqualTo(1)
    }

    @Test fun `recognizer error retains specific failure and releases resources`() = runTest {
        val recognizer = FakeRecognizer()
        val input = PlatformSpeechInput({ true }, { recognizer })
        val failure = async { runCatching { input.recognize().toList() }.exceptionOrNull() }
        runCurrent()
        recognizer.callbacks.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        runCurrent()
        assertThat((failure.await() as AiException).failure).isInstanceOf(AiFailure.Busy::class.java)
        assertThat(recognizer.destroyCalls).isEqualTo(1)
    }

    @Test fun `second collector cannot take microphone ownership from first`() = runTest {
        val recognizer = FakeRecognizer()
        var creates = 0
        val input = PlatformSpeechInput({ true }, { creates++; recognizer })
        val first = async { input.recognize().toList() }
        runCurrent()
        val second = async { runCatching { input.recognize().toList() }.exceptionOrNull() }
        runCurrent()
        assertThat((second.await() as AiException).failure).isInstanceOf(AiFailure.Busy::class.java)
        assertThat(creates).isEqualTo(1)
        assertThat(recognizer.destroyCalls).isEqualTo(0)
        first.cancel()
        runCurrent()
        assertThat(recognizer.destroyCalls).isEqualTo(1)
    }

    @Test fun `release finishes collection and remains safe when called twice`() = runTest {
        val recognizer = FakeRecognizer()
        val input = PlatformSpeechInput({ true }, { recognizer })
        val work = async { input.recognize().toList() }
        runCurrent()
        input.release()
        input.release()
        runCurrent()
        assertThat(work.await()).isEmpty()
        assertThat(recognizer.cancelCalls).isEqualTo(1)
        assertThat(recognizer.destroyCalls).isEqualTo(1)
    }

    @Test fun `Stop requests final speech without destroying active recognizer`() = runTest {
        val recognizer = FakeRecognizer()
        val input = PlatformSpeechInput({ true }, { recognizer })
        val work = async { input.recognize().toList() }
        runCurrent()
        input.stop()
        assertThat(recognizer.stopCalls).isEqualTo(1)
        assertThat(recognizer.destroyCalls).isEqualTo(0)
        recognizer.callbacks.onResults(text("last words"))
        runCurrent()
        assertThat(work.await()).contains(TranscriptChunk.Final("last words"))
        assertThat(recognizer.destroyCalls).isEqualTo(1)
    }

    @Test fun `unsupported device never creates a general network recognizer`() = runTest {
        var created = false
        val input = PlatformSpeechInput({ false }, { created = true; FakeRecognizer() })
        val failure = async { runCatching { input.recognize().toList() }.exceptionOrNull() }
        runCurrent()
        assertThat((failure.await() as AiException).failure).isInstanceOf(AiFailure.Unsupported::class.java)
        assertThat(created).isFalse()
    }

    @Test fun `factory failure does not poison the next attempt`() = runTest {
        var fail = true
        val recognizer = FakeRecognizer()
        val input = PlatformSpeechInput({ true }, { if (fail) error("service unavailable"); recognizer })
        val failure = async { runCatching { input.recognize().toList() }.exceptionOrNull() }
        runCurrent()
        assertThat(failure.await()).isInstanceOf(AiException::class.java)
        fail = false
        val retry = async { input.recognize().toList() }
        runCurrent()
        assertThat(recognizer.intent).isNotNull()
        retry.cancel()
        runCurrent()
    }

    @Test fun `Android adapter configures intent and destroys actual shadow recognizer on cancellation`() = runTest {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val input = PlatformSpeechInput(ApplicationProvider.getApplicationContext())
        val work = async { input.recognize().toList() }
        runCurrent()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertThat(recognizer.lastRecognizerIntent.action).isEqualTo(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        assertThat(recognizer.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)).isTrue()
        work.cancel()
        runCurrent()
        assertThat(recognizer.isDestroyed).isTrue()
    }

    private fun text(value: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value))
    }

    private class FakeRecognizer : PlatformRecognizer {
        lateinit var callbacks: RecognitionListener
        var intent: Intent? = null
        var startFails = false
        var listenerFails = false
        var cancelFails = false
        var cancelCalls = 0
        var destroyCalls = 0
        var stopCalls = 0
        override fun setListener(listener: RecognitionListener) {
            if (listenerFails) error("listener failed")
            callbacks = listener
        }
        override fun start(intent: Intent) {
            if (startFails) error("start failed")
            this.intent = intent
        }
        override fun stop() { stopCalls++ }
        override fun cancel() { cancelCalls++; if (cancelFails) error("cancel failed") }
        override fun destroy() { destroyCalls++ }
    }
}
