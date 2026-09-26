package com.localai.toolkit.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.core.navigation.ToolCatalog
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.ToolId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HomeControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `every tool card opens its own catalog route`() {
        val opened = mutableListOf<String>()
        compose.setContent { LocalAiTheme { HomeContent(HomePreviewStates.supported, opened::add) } }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ToolCatalog.entries.forEach { entry ->
            val title = context.getString(entry.titleRes)
            scrollTo(title)
            compose.onNodeWithText(title).assertIsEnabled().performClick()
        }
        assertThat(opened).containsExactlyElementsIn(ToolCatalog.entries.map { it.route }).inOrder()
    }

    @Test fun `quick text image audio and parent new task actions have distinct routes`() {
        val opened = mutableListOf<String>()
        compose.setContent { LocalAiTheme { HomeContent(HomePreviewStates.supported, opened::add) } }
        listOf("Add text", "Add image", "Add audio", "New task").forEach {
            compose.onNodeWithText(it).performClick()
        }
        assertThat(opened).containsExactly(Destination.ASK, Destination.IMAGE, Destination.TRANSCRIBE, Destination.ASK).inOrder()
    }

    @Test fun `quick actions on a device without Gemini Nano open usable local tools`() {
        val opened = mutableListOf<String>()
        compose.setContent { LocalAiTheme { HomeContent(HomePreviewStates.unsupported, opened::add) } }

        listOf("Translate text", "Extract text", "Add audio", "New task").forEach {
            compose.onNodeWithText(it).performClick()
        }

        assertThat(opened).containsExactly(
            Destination.TRANSLATE, Destination.OCR, Destination.TRANSCRIBE,
            Destination.TRANSLATE,
        ).inOrder()
    }

    @Test fun `large system font keeps audio shortcut on one readable line`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                LocalAiTheme { HomeContent(HomePreviewStates.supported, {}) }
            }
        }

        val label = compose.onNodeWithText("Add audio").getUnclippedBoundsInRoot()
        assertThat((label.right - label.left).value).isGreaterThan(50f)
        assertThat((label.bottom - label.top).value).isLessThan(64f)
    }

    @Test fun `extra large system font keeps Workspace header sections separate`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LocalAiTheme { HomeContent(HomePreviewStates.unsupported, {}) }
            }
        }

        val brand = compose.onNodeWithText("MindKit").getUnclippedBoundsInRoot()
        val tagline = compose.onNodeWithText("OFFLINE TOOLS FOR CLEARER WORK")
            .getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Your private workspace").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithText("Everything happens on this device.")
            .getUnclippedBoundsInRoot()
        assertThat(tagline.top.value).isAtLeast(brand.bottom.value)
        assertThat(title.top.value).isAtLeast(tagline.bottom.value)
        assertThat(body.top.value).isAtLeast(title.bottom.value)
    }

    @Test fun `unsupported transcription is not offered as a New task shortcut`() {
        val state = HomePreviewStates.unsupported.copy(
            tools = HomePreviewStates.unsupported.tools.map { tool ->
                if (tool.toolId == ToolId.TRANSCRIBE) {
                    tool.copy(status = AiCapabilityStatus.UNSUPPORTED)
                } else tool
            },
        )
        compose.setContent { LocalAiTheme { HomeContent(state, {}) } }

        compose.onNodeWithText("Add audio").assertDoesNotExist()
        compose.onNodeWithText("Start with text or an image").assertIsDisplayed()
    }

    @Test fun `unsupported cards stay disabled while bundled and deterministic tools remain available`() {
        var opened = 0
        compose.setContent { LocalAiTheme { HomeContent(HomePreviewStates.unsupported, { opened++ }) } }
        scrollTo("Ask AI")
        compose.onNodeWithText("Ask AI").assertIsNotEnabled().performClick()
        assertThat(opened).isEqualTo(0)
        listOf("Extract Text", "Developer").forEach {
            scrollTo(it)
            compose.onNodeWithText(it).assertIsEnabled().performClick()
        }
        assertThat(opened).isEqualTo(2)
    }

    @Test fun `recent result opens its exact history id`() {
        var opened: Long? = null
        val item = HistoryItem(42, HistoryType.SUMMARY, "Saved meeting", "Input", "Output", 1)
        compose.setContent {
            LocalAiTheme { HomeContent(HomePreviewStates.supported.copy(recentItems = listOf(item)), {},
                onOpenHistoryItem = { opened = it }) }
        }
        scrollTo(item.title)
        compose.onNodeWithText(item.title).performClick()
        assertThat(opened).isEqualTo(42L)
    }

    @Test fun `history failure exposes a visible Retry rather than pretending history is empty`() {
        var retries = 0
        compose.setContent {
            LocalAiTheme { HomeContent(HomePreviewStates.supported.copy(historyFailed = true), {},
                onRetryHistory = { retries++ }) }
        }
        scrollTo("Retry")
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        assertThat(retries).isEqualTo(1)
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }
}
