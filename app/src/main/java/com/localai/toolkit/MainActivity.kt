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
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.core.ui.LocalAiApp
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.feature.share.IncomingShareStore
import com.localai.toolkit.feature.share.toSharedContent
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

        // Captured before the first composition so the start destination can account for
        // it, rather than flashing Home and then jumping.
        val launchedWithShare = captureShare(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val settings = (state as? AppUiState.Ready)?.settings

            // While settings load, the theme shell still renders so there is no flash of
            // the wrong colour scheme before the stored preference arrives.
            LocalAiTheme(
                themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = settings?.dynamicColor ?: true,
            ) {
                if (settings != null) {
                    // Computed once and remembered without a key: the start destination is
                    // where navigation *began*. Recomputing it when the user finishes
                    // onboarding, or when the share is consumed, would reset the graph
                    // underneath them.
                    val startDestination = remember {
                        when {
                            launchedWithShare -> Destination.SHARE_ROUTER
                            settings.onboardingCompleted -> Destination.HOME
                            else -> Destination.ONBOARDING
                        }
                    }

                    LocalAiApp(startDestination = startDestination)
                }
            }
        }
    }

    /**
     * Handles a share that arrives while the app is already running.
     *
     * The activity is singleTask, so a second share re-enters here rather than creating
     * another instance. Recreating is the simplest correct way to re-evaluate the start
     * destination, and the share itself is already safely in the store.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (captureShare(intent)) {
            recreate()
        }
    }

    /** @return true when [intent] carried content the app can act on. */
    private fun captureShare(intent: Intent?): Boolean {
        val content = intent?.toSharedContent() ?: return false
        incomingShareStore.set(content)
        return true
    }
}
