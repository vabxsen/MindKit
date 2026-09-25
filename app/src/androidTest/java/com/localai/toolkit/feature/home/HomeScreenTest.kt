package com.localai.toolkit.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.localai.toolkit.R
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import org.junit.Rule
import org.junit.Test

/**
 * Home, driven through its stateless content composable.
 *
 * Testing [HomeContent] rather than [HomeScreen] keeps these tests free of Hilt and of
 * any real capability probing, so they assert the UI contract on any device - including
 * one with no Gemini Nano at all.
 */
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyToolIsListed() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.supported, onOpenTool = {}) }
        }

        listOf("Ask AI", "Summarize", "Rewrite", "Proofread", "Extract Text").forEach { title ->
            scrollTo(title).assertIsDisplayed()
        }
        listOf("Translate", "Image AI", "Transcribe", "Developer").forEach { title ->
            scrollTo(title).assertIsDisplayed()
        }
    }

    @Test
    fun aSupportedDeviceReportsReady() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.supported, onOpenTool = {}) }
        }

        assertReadiness("AI Ready", R.string.home_status_ready_description)
    }

    @Test
    fun anUnsupportedToolStaysVisibleAndExplainsItself() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.unsupported, onOpenTool = {}) }
        }

        // Hiding unsupported tools would leave the user with no idea why a feature they
        // read about is missing.
        assertReadiness("Limited Device Support", R.string.home_status_limited_description)
        scrollTo("Ask AI").assertIsDisplayed()
    }

    @Test
    fun anUnsupportedToolCannotBeOpened() {
        var opened: String? = null
        composeRule.setContent {
            LocalAiTheme {
                HomeContent(
                    state = HomePreviewStates.unsupported,
                    onOpenTool = { route -> opened = route },
                )
            }
        }

        scrollTo("Ask AI").performClick()

        assertThat(opened).isNull()
    }

    @Test
    fun aSupportedToolOpensItsRoute() {
        var opened: String? = null
        composeRule.setContent {
            LocalAiTheme {
                HomeContent(
                    state = HomePreviewStates.supported,
                    onOpenTool = { route -> opened = route },
                )
            }
        }

        scrollTo("Summarize").performClick()

        assertThat(opened).isEqualTo("tool/summarize")
    }

    @Test
    fun toolsThatDoNotNeedGeminiNanoStayUsableOnAnUnsupportedDevice() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.unsupported, onOpenTool = {}) }
        }

        // Text recognition is bundled and the developer utilities are deterministic, so
        // neither should be disabled just because Gemini Nano is missing.
        scrollTo("Extract Text").assertIsEnabled()
        scrollTo("Developer").assertIsEnabled()
    }

    @Test
    fun aDeviceNeedingADownloadSaysSoRatherThanDisablingTheTool() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.modelRequired, onOpenTool = {}) }
        }

        assertReadiness("Model Required", R.string.home_status_model_required_description)
        scrollTo("Summarize").assertIsEnabled()
    }

    @Test
    fun anUnresolvedDeviceShowsCheckingRatherThanUnsupported() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.checking, onOpenTool = {}) }
        }

        assertReadiness("Checking device", R.string.loading_checking_availability)
    }

    @Test
    fun unsupportedToolsAreDisabledButStillPresent() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.unsupported, onOpenTool = {}) }
        }

        scrollTo("Image AI").assertIsNotEnabled()
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        // Off-screen lazy items need list-level scrolling before node lookup.
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return composeRule.onNodeWithText(text)
    }

    private fun assertReadiness(label: String, descriptionRes: Int) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // StatusChip deliberately exposes one descriptive TalkBack node instead of
        // its visual children. Verify both that node and the actual rendered label.
        composeRule.onNodeWithContentDescription(context.getString(descriptionRes)).assertIsDisplayed()
        composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
    }
}
