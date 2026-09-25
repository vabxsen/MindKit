package com.localai.toolkit.feature.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.localai.toolkit.R
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.repository.HistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

enum class HistorySaveFeedback { SAVED, DISABLED, FAILED }

/** One implementation of Save's deduplication, failure recovery, and user feedback. */
class HistorySaveController(
    private val repository: HistoryRepository,
    private val scope: CoroutineScope,
) {
    private data class SaveKey(val slot: Any, val item: HistoryItem)
    private val pending = mutableSetOf<SaveKey>()
    private data class Watch(val job: Job, val isCurrent: () -> Boolean)
    private val watches = mutableMapOf<Any, Watch>()
    private val messages = Channel<HistorySaveFeedback>(Channel.BUFFERED)
    val feedback: Flow<HistorySaveFeedback> = messages.receiveAsFlow()

    // A tool has one result slot; Ask supplies a message ID for each independent answer.
    fun save(
        item: HistoryItem,
        isCurrent: () -> Boolean,
        slot: Any = Unit,
        onSavedChanged: (Boolean) -> Unit,
    ) {
        watches.filterValues { !it.isCurrent() }.keys.toList().forEach { key ->
            watches.remove(key)?.job?.cancel()
        }
        // Ignore timestamp differences from repeated taps on the same result, but
        // don't discard another answer's Save just because its text is identical.
        val key = SaveKey(slot, item.copy(id = 0, createdAtEpochMillis = 0))
        if (!pending.add(key)) return
        scope.launch {
            try {
                val id = repository.save(item)
                if (id == null) {
                    messages.send(HistorySaveFeedback.DISABLED)
                } else {
                    if (isCurrent()) {
                        onSavedChanged(true)
                        watchDeletion(id, slot, isCurrent, onSavedChanged)
                    }
                    messages.send(HistorySaveFeedback.SAVED)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                messages.send(HistorySaveFeedback.FAILED)
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun watchDeletion(
        id: Long,
        slot: Any,
        isCurrent: () -> Boolean,
        onSavedChanged: (Boolean) -> Unit,
    ) {
        watches.remove(slot)?.job?.cancel()
        // Register before starting, even when the supplied scope dispatches immediately.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val row = flow { emitAll(repository.observeById(id)) }
                    .retryWhen { cause, _ ->
                        if (cause is CancellationException) throw cause
                        if (cause !is Exception || !isCurrent()) return@retryWhen false
                        // A failed read is not proof of deletion. Retain the confirmed
                        // save and reconnect so later deletion can still be observed.
                        delay(5_000)
                        true
                    }
                    .first { it == null || !isCurrent() }
                if (row == null && isCurrent()) onSavedChanged(false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The result was replaced during read recovery; don't mutate it.
            } finally {
                if (watches[slot]?.job == currentCoroutineContext().job) watches.remove(slot)
            }
        }
        watches[slot] = Watch(job, isCurrent)
        job.start()
    }
}

@Composable
fun ObserveHistorySaveFeedback(feedback: Flow<HistorySaveFeedback>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(feedback, snackbar, lifecycle, resources) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            feedback.collect { result ->
                val message = when (result) {
                    HistorySaveFeedback.SAVED -> R.string.saved_to_history
                    HistorySaveFeedback.DISABLED -> R.string.history_disabled_not_saved
                    HistorySaveFeedback.FAILED -> R.string.history_save_failed
                }
                snackbar.showSnackbar(resources.getString(message))
            }
        }
    }
}
