package com.localai.toolkit.core.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.AppUiState
import com.localai.toolkit.domain.model.AppSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppRootTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `cold failure shows Retry instead of a blank screen`() {
        var retries = 0
        compose.setContent { AppRoot(AppUiState.Failed(), { retries++ }) { Text("App content") } }
        compose.onNodeWithText("App content").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test fun `recovering preferences preserves the mounted screen and its draft`() {
        val prefs = AppSettings(onboardingCompleted = true)
        val state = mutableStateOf<AppUiState>(AppUiState.Ready(prefs))
        compose.setContent {
            AppRoot(state.value, { state.value = AppUiState.Ready(prefs) }) {
                val draft = remember { mutableStateOf("") }
                TextField(draft.value, { draft.value = it }, label = { Text("Draft") })
            }
        }
        compose.onNodeWithText("Draft").performTextInput("Keep this draft")
        compose.runOnIdle { state.value = AppUiState.Failed(prefs) }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Keep this draft").assertExists()
    }
}
