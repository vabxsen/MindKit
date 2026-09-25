package com.localai.toolkit.feature

import android.content.Context
import android.graphics.Bitmap
import com.localai.toolkit.feature.image.*
import com.localai.toolkit.feature.ask.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.*
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.domain.usecase.devtools.DevTools
import com.localai.toolkit.feature.summarize.*
import com.localai.toolkit.feature.rewrite.*
import com.localai.toolkit.feature.proofread.*
import com.localai.toolkit.feature.translate.*
import com.localai.toolkit.feature.developer.*
import org.junit.Rule
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class OptionControlsTest {
    @get:Rule val compose = createComposeRule()
    private val ready = AiCapability(AiTask.ASK, AiCapabilityStatus.AVAILABLE, AiProvider.FAKE)
    private val idle = ModelDownloadState.Idle

    @Test fun `summary options Run and Clear are wired`() {
        val lengths = mutableListOf<SummaryLength>()
        val types = mutableListOf<SummaryInputType>()
        var runs = 0
        var clears = 0
        compose.setContent { LocalAiTheme {
            SummarizeContent(SummarizeUiState(input = "Input"), ready, idle, false,
                onInputChange = {}, onLengthChange = lengths::add, onInputTypeChange = types::add,
                onSummarize = { runs++ }, onSave = {}, onClear = { clears++ }, onDownload = {},
                onRetryCheck = {}, onSendTo = {}, onNavigateUp = {})
        } }
        listOf("Short", "Medium", "Detailed").forEach(::click)
        listOf("Article", "Conversation").forEach(::click)
        click("Summarize")
        click("Clear")
        assertThat(lengths).containsExactlyElementsIn(SummaryLength.entries).inOrder()
        assertThat(types).containsExactlyElementsIn(SummaryInputType.entries).inOrder()
        assertThat(runs).isEqualTo(1)
        assertThat(clears).isEqualTo(1)
    }

    @Test fun `rewrite styles comparison accept regeneration and Clear are wired`() {
        val styles = mutableListOf<RewriteStyle>()
        var comparisons = 0
        var accepts = 0
        var runs = 0
        var clears = 0
        compose.setContent { LocalAiTheme {
            RewriteContent(RewriteUiState(input = "Input", rewritten = "Output", comparedOriginal = "Input"), ready, idle, false,
                onInputChange = {}, onStyleChange = styles::add, onRewrite = { runs++ }, onAccept = { accepts++ },
                onToggleComparison = { comparisons++ }, onSave = {}, onClear = { clears++ }, onDownload = {},
                onRetryCheck = {}, onSendTo = {}, onNavigateUp = {})
        } }
        listOf("Rephrase", "Professional", "Friendly", "Shorten", "Elaborate", "Emojify").forEach(::click)
        click("Compare with original")
        click("Use this version")
        // Accept displays a transient snackbar over the bottom of the scroll area.
        compose.mainClock.advanceTimeBy(5_000)
        click("Regenerate")
        click("Clear")
        assertThat(styles).containsExactlyElementsIn(RewriteStyle.entries).inOrder()
        assertThat(comparisons).isEqualTo(1)
        assertThat(accepts).isEqualTo(1)
        assertThat(runs).isEqualTo(1)
        assertThat(clears).isEqualTo(1)
    }

    @Test fun `proofread input types Run Apply and Clear are wired`() {
        val types = mutableListOf<ProofreadInputType>()
        var applies = 0
        var runs = 0
        var clears = 0
        compose.setContent { LocalAiTheme {
            ProofreadContent(ProofreadUiState(input = "Input", corrected = "Output", comparedOriginal = "Input"), ready, idle, false,
                onInputChange = {}, onInputTypeChange = types::add, onProofread = { runs++ }, onApply = { applies++ },
                onSave = {}, onClear = { clears++ }, onDownload = {}, onRetryCheck = {}, onSendTo = {}, onNavigateUp = {})
        } }
        listOf("Keyboard", "Voice").forEach(::click)
        click("Proofread")
        click("Apply")
        click("Clear")
        assertThat(types).containsExactlyElementsIn(ProofreadInputType.entries).inOrder()
        assertThat(runs).isEqualTo(1)
        assertThat(applies).isEqualTo(1)
        assertThat(clears).isEqualTo(1)
    }

    // Robolectric 4.17 / Compose 1.12.1 on SDK 34 also times out with only a
    // Material AlertDialog + OutlinedTextField (no app code). Keep the JVM limitation
    // visible; LanguagePickerInstrumentedTest passed on an API 36 emulator.
    @Ignore("Dialog text-field layout never idles in Robolectric; equivalent Android regression passes on API 36")
    @Test fun `translation language search selection close detection swap and same-language guard work`() {
        val state = mutableStateOf(TranslateUiState(input = "Input", detectedSourceCode = "fr",
            languages = listOf(TranslationLanguage("en", "English"), TranslationLanguage("es", "Spanish"), TranslationLanguage("fr", "French")),
            downloadedLanguages = setOf("en", "es", "fr")))
        var swaps = 0
        var detected = 0
        compose.setContent { LocalAiTheme {
            TranslateContent(state.value, false, {},
                onSourceChange = { state.value = state.value.copy(sourceCode = it) },
                onTargetChange = { state.value = state.value.copy(targetCode = it) }, onSwap = { swaps++ },
                onTranslate = {}, onDownloadLanguage = {}, onAcceptDetected = { detected++ }, onSave = {}, onClear = {}, onNavigateUp = {})
        } }
        compose.onNode(hasText("From") and hasClickAction()).performClick()
        compose.onNodeWithText("Search languages").performTextInput("French")
        compose.onNode(hasText("French") and hasClickAction() and !hasSetTextAction()).performClick()
        assertThat(state.value.sourceCode).isEqualTo("fr")
        compose.onNode(hasText("To") and hasClickAction()).performClick()
        compose.onNodeWithText("Close").performClick()
        assertThat(state.value.targetCode).isEqualTo("es")
        click("Detected: French")
        compose.onNodeWithContentDescription("Swap languages").performScrollTo().performClick()
        assertThat(detected).isEqualTo(1)
        assertThat(swaps).isEqualTo(1)
        compose.runOnIdle { state.value = state.value.copy(targetCode = "fr") }
        compose.onNode(hasText("Translate") and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }

    @Test fun `translation model check Retry is separate from inference and loading blocks Run`() {
        val state = mutableStateOf(TranslateUiState(input = "Input", downloadedLanguages = setOf("en", "es"),
            modelFailure = AiFailure.Unknown()))
        var checks = 0
        var runs = 0
        compose.setContent { LocalAiTheme {
            TranslateContent(state.value, false, {}, {}, {}, {}, onTranslate = { runs++ },
                onDownloadLanguage = {}, onAcceptDetected = {}, onSave = {}, onClear = {}, onNavigateUp = {},
                onRefreshModels = { checks++ })
        } }
        click("Retry")
        assertThat(checks).isEqualTo(1)
        assertThat(runs).isEqualTo(0)
        compose.onNode(hasText("Translate") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(modelFailure = null, isRefreshingModels = true) }
        compose.onNode(hasText("Translate") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(isRefreshingModels = false) }
        click("Translate")
        assertThat(runs).isEqualTo(1)
    }

    @Test fun `translation failed download Retry invokes download recovery not inference`() {
        var retries = 0
        var runs = 0
        compose.setContent { LocalAiTheme {
            TranslateContent(TranslateUiState(failure = AiFailure.DownloadFailed(), failedDownloadCode = "es"),
                false, {}, {}, {}, {}, onTranslate = { runs++ }, onDownloadLanguage = {},
                onAcceptDetected = {}, onSave = {}, onClear = {}, onNavigateUp = {},
                onRetryFailure = { retries++ })
        } }
        click("Retry")
        assertThat(retries).isEqualTo(1)
        assertThat(runs).isEqualTo(0)
    }

    @Test fun `image modes question suggestions Run OCR and Clear are wired`() {
        val state = mutableStateOf(ImageUiState(preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)))
        val modes = mutableListOf<ImageMode>()
        val questions = mutableListOf<String>()
        var runs = 0
        var ocr = 0
        var clears = 0
        compose.setContent { LocalAiTheme {
            ImageContent(state.value, ready, idle, false, onImageSelected = {},
                onModeChange = { modes += it; state.value = state.value.copy(mode = it) },
                onQuestionChange = { questions += it; state.value = state.value.copy(question = it) },
                onRun = { runs++ }, onStop = {}, onSave = {}, onClear = { clears++ }, onDownload = {},
                onRetryCheck = {}, onRunOcr = { ocr++ }, onSendTextTo = {}, onNavigateUp = {})
        } }
        click("Describe image")
        click("Ask about it")
        compose.onNode(hasText("Ask") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        val prompts = listOf("What is in this image?", "Explain this screenshot.", "What error is shown?",
            "Describe this UI.", "Summarize this diagram.")
        prompts.forEach(::click)
        click("Ask")
        click("Extract text from this image")
        click("Clear")
        click("Describe")
        assertThat(questions).containsExactlyElementsIn(prompts).inOrder()
        assertThat(modes).containsExactly(ImageMode.ASK, ImageMode.DESCRIBE).inOrder()
        assertThat(runs).isEqualTo(2)
        assertThat(ocr).isEqualTo(1)
        assertThat(clears).isEqualTo(1)
    }

    @Test fun `Ask input send stop new conversation and information dialog work`() {
        val state = mutableStateOf(AskUiState())
        var sends = 0
        var stops = 0
        var resets = 0
        compose.setContent { LocalAiTheme {
            AskContent(state.value, ready, idle, false,
                onInputChange = { state.value = state.value.copy(input = it) }, onSend = { sends++ },
                onStop = { stops++ }, onRetry = {}, onNewConversation = { resets++ }, onSave = {},
                onDownload = {}, onRetryCheck = {}, onNavigateUp = {})
        } }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Ask something").performTextInput("Hello MindKit")
        compose.onNodeWithContentDescription("Send").performClick()
        assertThat(state.value.input).isEqualTo("Hello MindKit")
        compose.runOnIdle { state.value = state.value.copy(isGenerating = true,
            messages = listOf(AskMessage(1, AskRole.USER, "Hello"))) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(com.localai.toolkit.R.string.ask_stop)).performClick()
        compose.onNodeWithContentDescription(context.getString(com.localai.toolkit.R.string.ask_new_conversation)).performClick()
        compose.onNodeWithContentDescription("More information").performClick()
        compose.onNodeWithText(context.getString(com.localai.toolkit.R.string.ask_info_title)).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText(context.getString(com.localai.toolkit.R.string.ask_info_title)).assertDoesNotExist()
        assertThat(sends).isEqualTo(1)
        assertThat(stops).isEqualTo(1)
        assertThat(resets).isEqualTo(1)
    }

    @Test fun `developer index opens every sub-tool`() {
        val opened = mutableListOf<DeveloperTool>()
        compose.setContent { LocalAiTheme { DeveloperListScreen(opened::add, {}) } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        DeveloperTool.entries.forEach { tool ->
            val label = context.getString(tool.titleRes)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
        }
        assertThat(opened).containsExactlyElementsIn(DeveloperTool.entries).inOrder()
    }

    @Test fun `developer option controls directions counts algorithms languages and Now are wired`() {
        val tool = mutableStateOf(DeveloperTool.BASE64)
        val directions = mutableListOf<Boolean>()
        val counts = mutableListOf<Int>()
        val algorithms = mutableListOf<DevTools.HashAlgorithm>()
        val languages = mutableListOf<ErrorLanguage>()
        var now = 0
        var runs = 0
        var clears = 0
        compose.setContent { LocalAiTheme {
            DeveloperToolContent(tool.value, DeveloperUiState(input = "Input"), ready, idle, false,
                onInputChange = {}, onRun = { runs++ }, onClear = { clears++ }, onSave = {}, onErrorLanguageChange = languages::add,
                onBase64DirectionChange = directions::add, onUrlDirectionChange = directions::add,
                onHashAlgorithmChange = algorithms::add, onUuidCountChange = counts::add, onUseNow = { now++ },
                onDownload = {}, onRetryCheck = {}, onNavigateUp = {})
        } }
        listOf(DeveloperTool.BASE64, DeveloperTool.URL_CODEC).forEach { selected ->
            compose.runOnIdle { tool.value = selected }
            click("Decode"); click("Encode"); click("Run"); click("Clear")
        }
        compose.runOnIdle { tool.value = DeveloperTool.UUID_GENERATOR }
        listOf(1, 5, 10, 25).forEach { click(it.toString()) }
        compose.runOnIdle { tool.value = DeveloperTool.HASH_GENERATOR }
        DevTools.HashAlgorithm.entries.forEach { click(it.displayName) }
        compose.runOnIdle { tool.value = DeveloperTool.EXPLAIN_CODE }
        val context = ApplicationProvider.getApplicationContext<Context>()
        ErrorLanguage.entries.forEach { click(context.getString(it.labelRes)) }
        compose.runOnIdle { tool.value = DeveloperTool.TIMESTAMP }
        click("Use current time")
        assertThat(directions).containsExactly(true, false, true, false).inOrder()
        assertThat(counts).containsExactly(1, 5, 10, 25).inOrder()
        assertThat(algorithms).containsExactlyElementsIn(DevTools.HashAlgorithm.entries).inOrder()
        assertThat(languages).containsExactlyElementsIn(ErrorLanguage.entries).inOrder()
        assertThat(now).isEqualTo(1)
        assertThat(runs).isEqualTo(2)
        assertThat(clears).isEqualTo(2)
    }

    @Test fun `translation preference Retry never invokes translation or downloads`() {
        var preferences = 0
        var runs = 0
        var downloads = 0
        compose.setContent { LocalAiTheme {
            TranslateContent(TranslateUiState(languagePreferencesFailed = true,
                downloadedLanguages = setOf("en", "es")), false, {}, {}, {}, {},
                onTranslate = { runs++ }, onDownloadLanguage = { downloads++ },
                onAcceptDetected = {}, onSave = {}, onClear = {}, onNavigateUp = {},
                onRetryLanguagePreferences = { preferences++ })
        } }
        click("Retry")
        assertThat(preferences).isEqualTo(1)
        assertThat(runs).isEqualTo(0)
        assertThat(downloads).isEqualTo(0)
    }

    private fun click(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
}
