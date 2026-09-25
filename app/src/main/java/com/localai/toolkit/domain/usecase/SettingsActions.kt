package com.localai.toolkit.domain.usecase

import com.localai.toolkit.di.ApplicationScope
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accepts preference/reset actions immediately, in one order across screens.
 *
 * Awaiting a result belongs to the screen; the accepted write belongs to the app.
 * Navigating away cancels feedback, not the write. This is process-lifetime work,
 * not a durable queue: an OS process kill can still interrupt an unfinished action.
 */
@Singleton
class SettingsActions @Inject constructor(
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutations = Mutex()

    fun setThemeMode(mode: ThemeMode) = submit { settings.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = submit { settings.setDynamicColor(enabled) }
    fun setSaveHistory(enabled: Boolean) = submit { settings.setSaveHistory(enabled) }
    fun setOnboardingCompleted(completed: Boolean) = submit { settings.setOnboardingCompleted(completed) }
    fun setVerboseErrors(enabled: Boolean) = submit { settings.setVerboseErrors(enabled) }
    fun setTranslateLanguages(source: String?, target: String?) =
        submit { settings.setTranslateLanguages(source, target) }

    fun clearHistory() = submit { history.deleteAll() }

    fun clearAllLocalData() = submit {
        // Keep both stages within the shared queue. A later preference action must
        // not jump between them. Storage failures remain visible to the caller;
        // this is not an atomic transaction across Room and DataStore.
        history.deleteAll()
        settings.clear()
    }

    private fun submit(action: suspend () -> Unit): Deferred<Unit> =
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().ensureActive()
            // Register before returning, rather than dispatching first and letting
            // a later screen's action overtake this one.
            mutations.withLock { action() }
        }
}
