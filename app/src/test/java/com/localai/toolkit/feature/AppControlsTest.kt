package com.localai.toolkit.feature

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.feature.history.*
import com.localai.toolkit.feature.onboarding.*
import com.localai.toolkit.feature.settings.SettingsContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AppControlsTest {
    @get:Rule val compose = createComposeRule()
    private val item = HistoryItem(42, HistoryType.SUMMARY, "Meeting notes", "Input", "Saved output", 1L)

    @Test fun `history detail reports clipboard and share failures`() {
        compose.setContent {
            val base = LocalContext.current
            val unavailable = object : ContextWrapper(base) {
                override fun getSystemService(name: String): Any? =
                    if (name == CLIPBOARD_SERVICE) null else super.getSystemService(name)
                override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
            }
            CompositionLocalProvider(LocalContext provides unavailable) {
                LocalAiTheme { HistoryDetailPage(HistoryDetailUiState(item, false), {}, {}) }
            }
        }
        compose.onNodeWithContentDescription("Copy").performClick()
        compose.onNodeWithText("Could not copy this text. Please try again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share").performClick()
        compose.onNodeWithText("Could not open the share sheet. Please try again.").assertIsDisplayed()
    }

    @Test fun `Settings licenses opens the viewer directly without navigating through About`() {
        val routes = mutableListOf<String>()
        settings(onNavigate = routes::add)
        compose.onNodeWithText("Open source licenses").performScrollTo().performClick()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertThat(shadowOf(context).nextStartedActivity.component?.className)
            .isEqualTo(com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity::class.java.name)
        assertThat(routes).isEmpty()
    }

    @Test fun `history clearing requires confirmation and Cancel preserves data`() {
        var cleared = 0
        settings(onClearHistory = { cleared++ })
        compose.onNodeWithText("Clear history").performScrollTo().performClick()
        assertThat(cleared).isEqualTo(0)
        compose.onNodeWithText("Cancel").performClick()
        assertThat(cleared).isEqualTo(0)
        compose.onNodeWithText("Clear history").performScrollTo().performClick()
        compose.onNodeWithText("Delete all").performClick()
        assertThat(cleared).isEqualTo(1)
    }

    @Test fun `reset confirmation is distinct from clearing history`() {
        var reset = 0
        settings(onClearAll = { reset++ })
        compose.onNodeWithText("Clear all local data").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(reset).isEqualTo(0)
        compose.onNodeWithText("Clear all local data").performScrollTo().performClick()
        compose.onNode(hasText("Clear all local data") and hasAnyAncestor(isDialog())).performClick()
        assertThat(reset).isEqualTo(1)
    }

    @Test fun `switch rows expose and update a single accessible toggle`() {
        val settings = mutableStateOf(AppSettings())
        compose.setContent {
            LocalAiTheme {
                SettingsContent(settings.value, true, SnackbarHostState(),
                    onThemeModeChange = { settings.value = settings.value.copy(themeMode = it) },
                    onDynamicColorChange = { settings.value = settings.value.copy(dynamicColor = it) },
                    onSaveHistoryChange = { settings.value = settings.value.copy(saveHistory = it) },
                    onVerboseErrorsChange = { settings.value = settings.value.copy(verboseErrors = it) },
                    onClearHistory = {}, onClearAllData = {}, onNavigate = {})
            }
        }
        compose.onNodeWithText("Dark").performClick().assertIsSelected()
        compose.onNodeWithText("Dynamic color").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Save history").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Verbose error details").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onAllNodes(isToggleable()).assertCountEquals(3)
    }

    @Test fun `unsupported dynamic color and reset-time mutations are disabled`() {
        settings(supported = false, clearing = true)
        compose.onNodeWithText("Dynamic color").assertIsNotEnabled()
        compose.onNodeWithText("Dark").assertIsNotEnabled()
        compose.onNodeWithText("Save history").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Clear history").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Clear all local data").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `settings navigation rows route to their advertised pages`() {
        val routes = mutableListOf<String>()
        settings(onNavigate = routes::add)
        listOf("Device AI status", "Models", "Privacy information", "About MindKit", "Debug AI capability")
            .forEach { compose.onNodeWithText(it).performScrollTo().performClick() }
        assertThat(routes).containsExactly(Destination.DEVICE_AI, Destination.MODELS,
            Destination.PRIVACY, Destination.ABOUT, Destination.DEVICE_AI).inOrder()
    }

    @Test fun `onboarding advances two pages then runs the explicit device check`() {
        var checked = 0
        compose.setContent {
            LocalAiTheme { OnboardingContent(OnboardingStage.INTRO, DeviceAiSnapshot(), { checked++ }, {}) }
        }
        repeat(2) { compose.onNodeWithText("Continue").performClick(); compose.waitForIdle() }
        assertThat(checked).isEqualTo(0)
        compose.onNodeWithText("Check My Device").performClick()
        assertThat(checked).isEqualTo(1)
    }

    @Test fun `pending onboarding check offers Skip and failed check offers Retry`() {
        val stage = mutableStateOf(OnboardingStage.CHECKING)
        var finished = 0
        var checked = 0
        compose.setContent {
            LocalAiTheme { OnboardingContent(stage.value, DeviceAiSnapshot(), { checked++ }, { finished++ }) }
        }
        compose.onNodeWithText("Skip").assertIsDisplayed().performClick()
        assertThat(finished).isEqualTo(1)
        compose.runOnIdle { stage.value = OnboardingStage.ERROR }
        compose.onNodeWithText("Retry").performClick()
        assertThat(checked).isEqualTo(1)
    }

    @Test fun `history row delete does not also open that row and delete-all can be canceled`() {
        val opened = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        var all = 0
        history(HistoryUiState(items = listOf(item), isLoading = false),
            onOpen = opened::add, onDelete = deleted::add, onDeleteAll = { all++ })
        compose.onNodeWithText(item.title).performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        assertThat(opened).containsExactly(42L)
        assertThat(deleted).containsExactly(42L)
        compose.onNodeWithContentDescription("Delete all").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(all).isEqualTo(0)
        compose.onNodeWithContentDescription("Delete all").performClick()
        compose.onNodeWithText("Delete all").performClick()
        assertThat(all).isEqualTo(1)
    }

    @Test fun `history search and Clear no-matches action reach their callbacks`() {
        var query = ""
        var cleared = 0
        history(HistoryUiState(query = "missing", isLoading = false),
            onQuery = { query = it }, onClear = { cleared++ })
        compose.onNodeWithText("Search results").performTextInput("notes")
        assertThat(query).isEqualTo("notes")
        compose.onNodeWithText("Clear").performClick()
        assertThat(cleared).isEqualTo(1)
    }

    @Test fun `history load failure Retry is actionable`() {
        var retried = 0
        history(HistoryUiState(isLoading = false, loadFailed = true), onRetry = { retried++ })
        compose.onNodeWithText("Retry").performClick()
        assertThat(retried).isEqualTo(1)
    }

    @Test fun `history detail Copy and Share use the stored output`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.setContent {
            LocalAiTheme { HistoryDetailPage(HistoryDetailUiState(item, false), {}, {}) }
        }
        compose.onNodeWithContentDescription("Copy").performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo(item.output)
        compose.onNodeWithContentDescription("Share").performClick()
        val chooser = shadowOf(context).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(item.output)
    }

    @Test fun `history detail loading does not flash missing result and failure retries`() {
        val state = mutableStateOf(HistoryDetailUiState())
        var retries = 0
        compose.setContent { LocalAiTheme { HistoryDetailPage(state.value, {}, { retries++ }) } }
        compose.onNodeWithText("Loading history…").assertIsDisplayed()
        compose.onNodeWithText("This result is no longer available.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy").assertDoesNotExist()
        compose.runOnIdle { state.value = HistoryDetailUiState(isLoading = false, loadFailed = true) }
        compose.onNodeWithText("Retry").performClick()
        assertThat(retries).isEqualTo(1)
    }

    private fun settings(
        supported: Boolean = true, clearing: Boolean = false,
        onClearHistory: () -> Unit = {}, onClearAll: () -> Unit = {}, onNavigate: (String) -> Unit = {},
    ) = compose.setContent {
        LocalAiTheme { SettingsContent(AppSettings(), supported, SnackbarHostState(), {}, {}, {}, {},
            onClearHistory, onClearAll, onNavigate, isClearing = clearing) }
    }

    private fun history(
        state: HistoryUiState, onOpen: (Long) -> Unit = {}, onDelete: (Long) -> Unit = {},
        onDeleteAll: () -> Unit = {}, onQuery: (String) -> Unit = {}, onClear: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) = compose.setContent {
        LocalAiTheme { HistoryContent(state, "", onQuery, {}, onClear, onDeleteAll, onDelete, onOpen,
            onRetryLoad = onRetry) }
    }
}
