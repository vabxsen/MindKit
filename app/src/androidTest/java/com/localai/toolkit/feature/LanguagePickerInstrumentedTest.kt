package com.localai.toolkit.feature

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.feature.translate.TranslateContent
import com.localai.toolkit.feature.translate.TranslateUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Needs Android: a minimal dialog/text-field also fails to idle in our Robolectric runtime. */
@RunWith(AndroidJUnit4::class)
class LanguagePickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchSelectCloseDetectSwapAndGuardSameLanguage() {
        val state = mutableStateOf(TranslateUiState(input = "Input", detectedSourceCode = "fr",
            languages = listOf(TranslationLanguage("en", "English"), TranslationLanguage("es", "Spanish"), TranslationLanguage("fr", "French")),
            downloadedLanguages = setOf("en", "es", "fr")))
        var swaps = 0
        var detected = 0
        compose.setContent { LocalAiTheme {
            TranslateContent(state.value, false, {},
                onSourceChange = { state.value = state.value.copy(sourceCode = it) },
                onTargetChange = { state.value = state.value.copy(targetCode = it) }, onSwap = { swaps++ },
                onTranslate = {}, onDownloadLanguage = {}, onAcceptDetected = { detected++ },
                onSave = {}, onClear = {}, onNavigateUp = {})
        } }
        compose.onNode(hasText("From") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Search languages").performTextInput("French")
        // The search field is also clickable and now contains "French".
        compose.onNode(hasText("French") and hasClickAction() and !hasSetTextAction()).performClick()
        assertThat(state.value.sourceCode).isEqualTo("fr")
        compose.onNode(hasText("To") and hasClickAction()).performClick()
        compose.onNodeWithText("Close").performClick()
        assertThat(state.value.targetCode).isEqualTo("es")
        compose.onNodeWithText("Detected: French").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Swap languages").performScrollTo().performClick()
        assertThat(detected).isEqualTo(1)
        assertThat(swaps).isEqualTo(1)
        compose.runOnIdle { state.value = state.value.copy(targetCode = "fr") }
        compose.onNode(hasText("Translate") and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }
}
