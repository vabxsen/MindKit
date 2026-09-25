package com.localai.toolkit

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.feature.share.IncomingShareStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real MainActivity, production graph, ViewModels, DataStore and dependency bindings.
 * Recreation tests configuration changes, not OS process death. No AI model is downloaded.
 */
@HiltAndroidTest
class MainActivityLifecycleTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var shares: IncomingShareStore
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalOnboarding: Boolean? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        hilt.inject()
        runBlocking {
            originalOnboarding = settings.settings.first().onboardingCompleted
            settings.setOnboardingCompleted(true)
        }
        shares.clear()
    }

    @After fun close() {
        try {
            if (scenario != null) scenario!!.close() else {
                val closing = mutableListOf<MainActivity>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    Stage.entries.filter { it != Stage.DESTROYED }.forEach { stage ->
                        closing += ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(stage).filterIsInstance<MainActivity>()
                    }
                    closing.distinct().forEach { it.finish() }
                }
                compose.waitUntil(20_000) { closing.all { it.isDestroyed } }
            }
        } finally {
            originalOnboarding?.let { previous -> runBlocking { settings.setOnboardingCompleted(previous) } }
        }
    }

    @Test fun developerDraftAndResultSurviveRecreationAndBackRestoresHome() {
        launch()
        openBase64()
        compose.onNode(hasSetTextAction()).performTextInput("hello")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Run").performScrollTo().performClick()
        compose.onNodeWithText("aGVsbG8=").performScrollTo().assertIsDisplayed()
        scenario!!.recreate()
        awaitNode(hasText("Text or Base64"))
        compose.onNode(hasSetTextAction()).assertTextContains("hello")
        compose.onNodeWithText("aGVsbG8=").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Workspace").assertDoesNotExist()
        Espresso.pressBack()
        awaitNode(hasText("Base64") and hasClickAction())
        compose.onNodeWithText("Workspace").assertDoesNotExist()
        Espresso.pressBack()
        awaitNode(hasText("Workspace") and isSelectable()).assertIsDisplayed()
    }

    @Test fun historySearchSurvivesTabSwitchesAndActivityRecreation() {
        launch()
        tab("History").performClick()
        awaitNode(hasText("Search results"))
        compose.onNode(hasSetTextAction()).performTextInput("meeting draft")
        Espresso.closeSoftKeyboard()
        repeat(2) {
            tab("Settings").performClick()
            tab("History").performClick()
        }
        scenario!!.recreate()
        awaitNode(hasText("Search results"))
        compose.onNode(hasSetTextAction()).assertTextContains("meeting draft")
        Espresso.pressBack()
        tab("Workspace").assertIsSelected()
    }

    @Test fun warmShareBackAndCancelReturnToTheExistingDraftWithoutReplayingIt() {
        // ActivityScenario filters by the original launch intent, so it stops
        // observing a singleTask activity after MAIN becomes SEND in onNewIntent.
        // Track the actual resumed instance instead; never modify the app's intent.
        launch(trackByIntent = false)
        openBase64()
        compose.onNode(hasSetTextAction()).performTextInput("Unsent draft")
        Espresso.closeSoftKeyboard()
        deliver(share("First shared text"))
        awaitNode(hasText("First shared text"))
        recreateCurrentActivity()
        awaitNode(hasText("First shared text"))
        Espresso.pressBack()
        awaitNode(hasText("Text or Base64"))
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        assertThat(shares.pending.value).isNull()
        recreateCurrentActivity()
        awaitNode(hasText("Text or Base64"))
        compose.onNodeWithText("Shared content detected").assertDoesNotExist()
        deliver(share("Second shared text"))
        awaitNode(hasText("Second shared text"))
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        awaitNode(hasText("Text or Base64"))
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        assertThat(shares.pending.value).isNull()
    }

    @Test fun coldShareLatestReplacementSurvivesLauncherAndRecreationThenRoutesOnce() {
        launch(share("First share"))
        awaitNode(hasText("First share"))
        deliver(share("Latest share"))
        awaitNode(hasText("Latest share"))
        compose.onNodeWithText("First share").assertDoesNotExist()
        deliver(mainIntent())
        scenario!!.onActivity { activity ->
            assertThat(activity.intent.action).isEqualTo(Intent.ACTION_SEND)
            assertThat(activity.intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Latest share")
        }
        scenario!!.recreate()
        awaitNode(hasText("Latest share"))
        compose.onNode(hasText("Translate") and hasClickAction()).performScrollTo().performClick()
        awaitNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains("Latest share")
        assertThat(shares.pending.value).isNull()
        scenario!!.recreate()
        awaitNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains("Latest share")
        compose.onNodeWithText("Shared content detected").assertDoesNotExist()
        Espresso.pressBack()
        tab("Workspace").assertIsSelected()
        scenario!!.recreate()
        tab("Workspace").assertIsSelected()
        compose.onNodeWithText("Shared content detected").assertDoesNotExist()
    }

    private fun launch(intent: Intent = mainIntent(), trackByIntent: Boolean = true) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (trackByIntent) scenario = ActivityScenario.launch(intent) else {
            context.startActivity(intent)
            compose.waitUntil(20_000) { resumedActivity() != null }
        }
        if (intent.action != Intent.ACTION_SEND) tab("Workspace")
    }

    private fun resumedActivity(): MainActivity? {
        var current: MainActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
        }
        return current
    }

    private fun recreateCurrentActivity() {
        val previous = checkNotNull(resumedActivity())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { previous.recreate() }
        compose.waitUntil(20_000) {
            resumedActivity()?.let { it !== previous && previous.isDestroyed } == true
        }
    }

    private fun mainIntent() = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun share(text: String) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    private fun deliver(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun openBase64() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Developer"))
        compose.onNode(hasText("Developer") and hasClickAction()).performClick()
        awaitNode(hasText("Base64"))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Base64"))
        compose.onNode(hasText("Base64") and hasClickAction()).performClick()
        awaitNode(hasText("Text or Base64"))
    }

    private fun tab(label: String) = awaitNode(hasText(label) and isSelectable())

    private fun awaitNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }
}
