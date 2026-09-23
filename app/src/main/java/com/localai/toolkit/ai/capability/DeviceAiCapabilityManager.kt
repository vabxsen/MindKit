package com.localai.toolkit.ai.capability

import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the app's answer to "what can this device actually do?".
 *
 * The snapshot is cached and shared so that Home, the tool screens and the Device AI
 * screen all agree without each re-running availability checks. Every value in it comes
 * from a runtime API call.
 */
interface DeviceAiCapabilityManager {

    /** The latest known capability snapshot. Starts empty and fills in asynchronously. */
    val snapshot: StateFlow<DeviceAiSnapshot>

    /**
     * Re-runs availability checks.
     *
     * @param force when false, a recent snapshot may be reused.
     */
    suspend fun refresh(force: Boolean = false)

    /** Re-checks a single task, e.g. right after a model download finishes. */
    suspend fun refresh(task: AiTask)
}
