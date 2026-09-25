package com.localai.toolkit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localai.toolkit.core.ui.AppRoot
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.core.ui.LocalAiApp
import com.localai.toolkit.feature.share.IncomingShareStore
import com.localai.toolkit.feature.share.toSharedContent
import com.localai.toolkit.feature.share.toSavedState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The app's single activity.
 *
 * Kept thin on purpose: it resolves the theme, captures anything shared into the app, and
 * picks a start destination, then hands off to Compose navigation. Configuration changes
 * are handled by Compose and the ViewModels rather than by recreating state here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var incomingShareStore: IncomingShareStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Recreate only an unconsumed share after process death. Rotation must not
        // replay a share that was already routed or dismissed.
        if (savedInstanceState == null) {
            captureShare(intent)
        } else if (savedInstanceState.getBoolean(SHARE_PENDING)) {
            // Android may relaunch with the original task intent, not the latest
            // onNewIntent payload. Prefer our normalized, unconsumed saved share.
            val restored = savedInstanceState.getBundle(SHARE_CONTENT)?.toSharedContent()
            if (restored != null) incomingShareStore.set(restored) else captureShare(intent)
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val pendingShare by incomingShareStore.pending.collectAsStateWithLifecycle()
            AppRoot(state = state, onRetry = viewModel::retry) { settings ->
                // Computed once: the start destination is where navigation began,
                // not a reason to reset the graph after a preference update.
                val startDestination = remember {
                    when {
                        settings.onboardingCompleted -> Destination.HOME
                        else -> Destination.ONBOARDING
                    }
                }
                LocalAiApp(startDestination = startDestination, hasPendingShare = pendingShare != null)
            }
        }
    }

    /**
     * Handles a share that arrives while the app is already running.
     *
     * The activity is singleTask, so a second share re-enters here rather than creating
     * another instance. The observable store navigates the existing graph, preserving
     * the current tool and avoiding conflicts with a restored start destination.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the pending share's recovery intent if the launcher merely brings
        // the activity forward. A new valid share replaces it; unrelated intents do not.
        if (captureShare(intent) || incomingShareStore.pending.value == null) {
            setIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val pending = incomingShareStore.pending.value
        outState.putBoolean(SHARE_PENDING, pending != null)
        if (pending != null) outState.putBundle(SHARE_CONTENT, pending.toSavedState())
        super.onSaveInstanceState(outState)
    }

    /** @return true when [intent] carried content the app can act on. */
    private fun captureShare(intent: Intent?): Boolean {
        val content = intent?.toSharedContent() ?: return false
        incomingShareStore.set(content)
        return true
    }

    private companion object {
        const val SHARE_PENDING = "mindkit.share.pending"
        const val SHARE_CONTENT = "mindkit.share.content"
    }
}
