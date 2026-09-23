package com.localai.toolkit

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Process entry point.
 *
 * Deliberately does no work on startup: no capability probing, no model warm-up and no
 * connectivity check. Availability is resolved lazily by
 * [com.localai.toolkit.ai.capability.DeviceAiCapabilityManager] once a screen needs it,
 * so cold start stays fast and the app opens fine with no network.
 */
@HiltAndroidApp
class LocalAiApplication : Application()
