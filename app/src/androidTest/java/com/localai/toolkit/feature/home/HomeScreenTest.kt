package com.localai.toolkit.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        listOf("Translate", "Image AI", "Transcribe", "Developer").forEach { title ->
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aSupportedDeviceReportsReady() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.supported, onOpenTool = {}) }
        }

        composeRule.onNodeWithText("AI Ready").assertIsDisplayed()
    }

    @Test
    fun anUnsupportedToolStaysVisibleAndExplainsItself() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.unsupported, onOpenTool = {}) }
        }

        // Hiding unsupported tools would leave the user with no idea why a feature they
        // read about is missing.
        composeRule.onNodeWithText("Ask AI").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Limited Device Support").assertIsDisplayed()
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

        composeRule.onNodeWithText("Ask AI").performScrollTo().performClick()

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

        composeRule.onNodeWithText("Summarize").performScrollTo().performClick()

        assertThat(opened).isEqualTo("tool/summarize")
    }

    @Test
    fun toolsThatDoNotNeedGeminiNanoStayUsableOnAnUnsupportedDevice() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.unsupported, onOpenTool = {}) }
        }

        // Text recognition is bundled and the developer utilities are deterministic, so
        // neither should be disabled just because Gemini Nano is missing.
        composeRule.onNodeWithText("Extract Text").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Developer").performScrollTo().assertIsEnabled()
    }

    @Test
    fun aDeviceNeedingADownloadSaysSoRatherThanDisablingTheTool() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.modelRequired, onOpenTool = {}) }
        }

        composeRule.onNodeWithText("Model Required").assertIsDisplayed()
        composeRule.onNodeWithText("Summarize").performScrollTo().assertIsEnabled()
    }

    @Test
    fun anUnresolvedDeviceShowsCheckingRatherThanUnsupported() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.checking, onOpenTool = {}) }
        }

        composeRule.onNodeWithText("Checking device").assertIsDisplayed()
    }

    @Test
    fun unsupportedToolsAreDisabledButStillPresent() {
        composeRule.setContent {
            LocalAiTheme { HomeContent(state = HomePreviewStates.unsupported, onOpenTool = {}) }
        }

        composeRule.onNodeWithText("Image AI").performScrollTo().assertIsNotEnabled()
    }
}
