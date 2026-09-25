package com.localai.toolkit.feature.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.core.navigation.ToolCatalog
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
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
