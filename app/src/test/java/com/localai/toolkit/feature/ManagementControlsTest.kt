package com.localai.toolkit.feature

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.R
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.core.util.labelRes
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.feature.capability.DeviceAiContent
import com.localai.toolkit.feature.history.HistoryContent
import com.localai.toolkit.feature.history.HistoryUiState
import com.localai.toolkit.feature.settings.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ManagementControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `About launches the bundled license viewer and wires device info and Back`() {
        val routes = mutableListOf<String>()
        var back = 0
        compose.setContent { LocalAiTheme { AboutScreen({ back++ }, routes::add) } }
        compose.onNodeWithText("Open source licenses").performScrollTo().performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertThat(intent.component?.className).isEqualTo(OssLicensesMenuActivity::class.java.name)
        assertThat(intent.data).isNull()
        assertThat(context.packageManager.resolveActivity(intent, 0)).isNotNull()
        compose.onNodeWithText(context.getString(R.string.settings_device_info)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_navigate_up)).performClick()
        assertThat(routes).containsExactly(Destination.DEVICE_AI)
        assertThat(back).isEqualTo(1)
    }

    @Test fun `license launch failure is visible instead of silently ignored`() {
        val failingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("test failure") }
        }
        compose.setContent { CompositionLocalProvider(LocalContext provides failingContext) {
            LocalAiTheme { AboutScreen({}, {}) }
        } }
        compose.onNodeWithText("Open source licenses").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.licenses_open_failed)).assertIsDisplayed()
    }

    @Test fun `Privacy content can be read through the caveat and Back returns`() {
        var back = 0
        compose.setContent { LocalAiTheme { PrivacyScreen({ back++ }) } }
        listOf(R.string.privacy_headline, R.string.privacy_point_no_account,
            R.string.privacy_permission_microphone, R.string.privacy_caveat_body).forEach {
            compose.onNodeWithText(context.getString(it)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.cd_navigate_up)).performClick()
        assertThat(back).isEqualTo(1)
    }

    @Test fun `device status Refresh Retry and Back work and duplicate refresh is disabled`() {
        val refreshing = mutableStateOf(false)
        var refreshes = 0
        var back = 0
        compose.setContent { LocalAiTheme {
            DeviceAiContent(DeviceAiSnapshot(), { back++ }, { refreshes++ },
                isRefreshing = refreshing.value, refreshFailed = true)
        } }
        val refresh = compose.onNodeWithContentDescription(context.getString(R.string.device_ai_refresh))
        refresh.performClick()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        assertThat(refreshes).isEqualTo(2)
        compose.runOnIdle { refreshing.value = true }
        refresh.assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_navigate_up)).performClick()
        assertThat(back).isEqualTo(1)
    }

    @Test fun `model search download delete and busy guards select the correct language`() {
        val state = mutableStateOf(ModelsUiState(isLoading = false, downloaded = setOf("en"),
            languages = listOf(TranslationLanguage("en", "English"), TranslationLanguage("fr", "French"))))
        val downloads = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        compose.setContent { LocalAiTheme {
            ModelsContent(state.value, { state.value = state.value.copy(query = it) },
                downloads::add, deletes::add, {}, {})
        } }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Download").performClick()
        assertThat(deletes).containsExactly("en")
        assertThat(downloads).containsExactly("fr")
        compose.onNodeWithText("Search languages").performTextInput("French")
        compose.onNodeWithText("English").assertDoesNotExist()
        compose.onNode(hasText("French") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Search languages").performTextClearance()
        compose.runOnIdle { state.value = state.value.copy(busyCode = "fr") }
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun `all history filter chips select and deselect their actual type`() {
        val state = mutableStateOf(HistoryUiState(isLoading = false))
        val selected = mutableListOf<HistoryType>()
        compose.setContent { LocalAiTheme {
            HistoryContent(state.value, "", {}, onToggleType = { type ->
                selected += type
                state.value = state.value.copy(selectedTypes =
                    if (type in state.value.selectedTypes) state.value.selectedTypes - type else state.value.selectedTypes + type)
            }, onClearFilters = {}, onDeleteAll = {}, onDelete = {}, onOpenItem = {})
        } }
        HistoryType.entries.forEach { type ->
            val label = context.getString(type.labelRes())
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            val chip = compose.onNode(hasText(label) and isSelectable())
            chip.performClick().assertIsSelected()
            chip.performClick().assertIsNotSelected()
        }
        assertThat(selected).containsExactlyElementsIn(HistoryType.entries.flatMap { listOf(it, it) }).inOrder()
    }
}
