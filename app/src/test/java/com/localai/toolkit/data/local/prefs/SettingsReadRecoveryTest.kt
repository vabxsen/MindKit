package com.localai.toolkit.data.local.prefs

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReadRecoveryTest {
    @Test fun `cold read failure disables history and keeps observing until storage recovers`() = runTest {
        var reads = 0
        val expected = AppSettings(themeMode = ThemeMode.DARK, onboardingCompleted = true)
        val values = mutableListOf<AppSettings>()
        val source = flow {
            if (++reads == 1) throw IOException("temporarily unavailable")
            emit(expected)
            awaitCancellation()
        }.recoverReadFailures()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.collect(values::add) }
        assertThat(values.single().storageReadFailed).isTrue()
        assertThat(values.single().saveHistory).isFalse()
        advanceTimeBy(5_001)
        runCurrent()
        assertThat(reads).isEqualTo(2)
        assertThat(values.last()).isEqualTo(expected)
    }

    @Test fun `read failure keeps known appearance while disabling writes and preserves cancellation`() = runTest {
        val expected = AppSettings(themeMode = ThemeMode.DARK, onboardingCompleted = true)
        val values = mutableListOf<AppSettings>()
        var reads = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow {
                reads++
                emit(expected)
                throw IOException("disk")
            }.recoverReadFailures().collect(values::add)
        }
        assertThat(values.last().themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(values.last().onboardingCompleted).isTrue()
        assertThat(values.last().saveHistory).isFalse()
        job.cancel()
        advanceTimeBy(10_000)
        assertThat(reads).isEqualTo(1)
    }
}
