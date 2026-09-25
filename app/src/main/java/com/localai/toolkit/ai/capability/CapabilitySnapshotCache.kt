package com.localai.toolkit.ai.capability

import android.os.SystemClock
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates cached snapshots independently of vendor probes. Freshness uses
 * elapsed realtime (including device sleep); wall-clock timestamps are metadata.
 */
internal class CapabilitySnapshotCache(
    private val ioDispatcher: CoroutineDispatcher,
    private val resolve: suspend (AiTask) -> AiCapability,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : DeviceAiCapabilityManager {
    private val state = MutableStateFlow(DeviceAiSnapshot())
    override val snapshot = state.asStateFlow()
    private val refreshLock = Mutex()
    // Guarded by refreshLock. A single-task update must not renew the full snapshot.
    private var lastFullCheckElapsedMillis: Long? = null

    override suspend fun refresh(force: Boolean) {
        refreshLock.withLock {
            val last = lastFullCheckElapsedMillis
            val fresh = last != null &&
                elapsedRealtimeMillis() - last in 0L until CACHE_DURATION_MILLIS
            if (!force && fresh) return

            state.value = state.value.copy(isRefreshing = true)
            try {
                val resolved = withContext(ioDispatcher) {
                    AiTask.entries.associateWith { task -> resolve(task) }
                }
                val completedAt = elapsedRealtimeMillis()
                state.value = DeviceAiSnapshot(
                    capabilities = resolved,
                    isRefreshing = false,
                    lastCheckedAtEpochMillis = wallClockMillis(),
                )
                lastFullCheckElapsedMillis = completedAt
            } finally {
                state.value = state.value.copy(isRefreshing = false)
            }
        }
    }

    override suspend fun refresh(task: AiTask) {
        // Resolve under the same lock as publication. Otherwise an older slow probe
        // can publish after a newer task/full refresh and undo its result.
        refreshLock.withLock {
            val resolved = withContext(ioDispatcher) { resolve(task) }
            state.value = state.value.copy(
                capabilities = state.value.capabilities + (task to resolved),
                lastCheckedAtEpochMillis = wallClockMillis(),
            )
        }
    }

    private companion object {
        const val CACHE_DURATION_MILLIS = 60_000L
    }
}
