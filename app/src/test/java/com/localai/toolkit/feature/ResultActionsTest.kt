package com.localai.toolkit.feature

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.engine.AskRole
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.designsystem.component.ResultCard
import com.localai.toolkit.feature.common.HistorySaveController
import com.localai.toolkit.testing.MemoryHistory
import kotlinx.coroutines.runBlocking
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.feature.ask.*
import com.localai.toolkit.feature.summarize.*
import com.localai.toolkit.feature.rewrite.*
import com.localai.toolkit.feature.proofread.*
import com.localai.toolkit.feature.ocr.*
import com.localai.toolkit.feature.translate.*
import com.localai.toolkit.feature.image.*
import com.localai.toolkit.feature.transcription.*
import com.localai.toolkit.feature.developer.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ResultActionsTest {
    @get:Rule val compose = createComposeRule()
    private val output = "Visible result — 日本語"
    private var saves = 0
    private val handoffs = mutableListOf<ToolId>()
    private val ready = AiCapability(AiTask.ASK, AiCapabilityStatus.AVAILABLE, AiProvider.FAKE)
    private val idle = ModelDownloadState.Idle
    private val save: () -> Unit = { saves++ }

    @Test fun `Saved button returns to an enabled Save after its history row is deleted`() {
        val history = MemoryHistory()
        val item = HistoryItem(type = HistoryType.SUMMARY, title = "Article",
            inputPreview = "Article", output = output, createdAtEpochMillis = 1)
        compose.setContent {
            val saved = remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val controller = remember { HistorySaveController(history, scope) }
            LocalAiTheme {
                ResultCard(
                    text = output, onCopy = {}, onShare = {},
                    saved = saved.value,
                    onSave = { controller.save(item, { true }) { saved.value = it } },
                )
            }
        }
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.onNodeWithText("Saved").assertIsNotEnabled()
        compose.runOnIdle { runBlocking { history.deleteAll() } }
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.onNodeWithText("Saved").assertIsNotEnabled()
        compose.runOnIdle { assertThat(history.items.value.single().output).isEqualTo(output) }
    }

    @Test fun `Ask result Copy Share and Save target the answer`() = verify(ToolId.ASK)
    @Test fun `Summary result Copy Share and Save target the summary`() = verify(ToolId.SUMMARIZE)
    @Test fun `Rewrite result Copy Share and Save target the rewrite`() = verify(ToolId.REWRITE)
    @Test fun `Proofread result Copy Share and Save target the correction not the original`() = verify(ToolId.PROOFREAD)
    @Test fun `OCR result Copy Share and Save target extracted text`() = verify(ToolId.OCR)
    @Test fun `Translate result Copy Share and Save target the translation`() = verify(ToolId.TRANSLATE)
    @Test fun `Image result Copy Share and Save target the description`() = verify(ToolId.IMAGE)
    @Test fun `Transcribe result Copy Share and Save target the transcript`() = verify(ToolId.TRANSCRIBE)
    @Test fun `Developer result Copy and Share contain the displayed validation message`() = verify(ToolId.DEVELOPER)

    @Test fun `Summary failed Copy gives honest feedback`() {
        show(ToolId.SUMMARIZE, unavailableClipboard = true)
        compose.onNode(hasText("Copy") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Could not copy this text. Please try again.").assertIsDisplayed()
    }

    @Test fun `Summary unavailable share sheet gives feedback instead of crashing`() {
        show(ToolId.SUMMARIZE, unavailableShare = true)
        compose.onNode(hasText("Share") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Could not open the share sheet. Please try again.").assertIsDisplayed()
    }

    @Test fun `Ask reports failed Copy and Share`() = verifyFailures(ToolId.ASK)
    @Test fun `Rewrite reports failed Copy and Share`() = verifyFailures(ToolId.REWRITE)
    @Test fun `Proofread reports failed Copy and Share`() = verifyFailures(ToolId.PROOFREAD)
    @Test fun `OCR reports failed Copy and Share`() = verifyFailures(ToolId.OCR)
    @Test fun `Translate reports failed Copy and Share`() = verifyFailures(ToolId.TRANSLATE)
    @Test fun `Image reports failed Copy and Share`() = verifyFailures(ToolId.IMAGE)
    @Test fun `Transcribe reports failed Copy and Share`() = verifyFailures(ToolId.TRANSCRIBE)
    @Test fun `Developer reports failed Copy and Share`() = verifyFailures(ToolId.DEVELOPER)

    @Test fun `Proofread original Share exports the original not the correction`() = verifyOriginalShare(ToolId.PROOFREAD, 0)
    @Test fun `Rewrite original Share exports the original not the rewrite`() = verifyOriginalShare(ToolId.REWRITE, 1)

    private fun verifyOriginalShare(tool: ToolId, index: Int) {
        show(tool, showOriginal = true)
        compose.onAllNodes(hasText("Share") and hasClickAction())[index].performScrollTo().performClick()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val chooser = shadowOf(context).nextStartedActivity
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Original text")
    }

    @Test @Config(sdk = [32])
    fun `Proofread original Copy confirms success on Android 12`() {
        show(ToolId.PROOFREAD)
        compose.onAllNodes(hasText("Copy") and hasClickAction())[0].performScrollTo().performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(com.localai.toolkit.R.string.copied_to_clipboard)).assertIsDisplayed()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("Original text")
    }

    @Test @Config(sdk = [32])
    fun `Rewrite original Copy confirms success on Android 12`() {
        show(ToolId.REWRITE, showOriginal = true)
        compose.onAllNodes(hasText("Copy") and hasClickAction())[1].performScrollTo().performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(com.localai.toolkit.R.string.copied_to_clipboard)).assertIsDisplayed()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("Original text")
    }

    private fun verifyFailures(tool: ToolId) {
        show(tool, unavailableClipboard = true, unavailableShare = true)
        val index = if (tool == ToolId.PROOFREAD) 1 else 0
        fun action(label: String) {
            val node = compose.onAllNodes(hasText(label) or hasContentDescription(label))[index]
            if (tool != ToolId.ASK) node.performScrollTo()
            node.performClick()
        }
        action("Copy")
        compose.onNodeWithText("Could not copy this text. Please try again.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        action("Share")
        compose.onNodeWithText("Could not open the share sheet. Please try again.").assertIsDisplayed()
    }

    @Test fun `summary follow-up chips select the advertised destination`() {
        show(ToolId.SUMMARIZE)
        listOf("Translate", "Rewrite").forEach {
            compose.onNode(hasText(it) and hasClickAction()).performScrollTo().performClick()
        }
        assertThat(handoffs).containsExactly(ToolId.TRANSLATE, ToolId.REWRITE).inOrder()
    }

    @Test fun `rewrite handoffs select Proofread and Translate`() = verifyHandoffs(ToolId.REWRITE, listOf("Proofread", "Translate"), listOf(ToolId.PROOFREAD, ToolId.TRANSLATE))
    @Test fun `proofread handoffs select Rewrite and Translate`() = verifyHandoffs(ToolId.PROOFREAD, listOf("Rewrite", "Translate"), listOf(ToolId.REWRITE, ToolId.TRANSLATE))
    @Test fun `OCR handoffs select all four advertised text tools`() = verifyHandoffs(ToolId.OCR, listOf("Summarize", "Translate", "Rewrite", "Ask AI"), listOf(ToolId.SUMMARIZE, ToolId.TRANSLATE, ToolId.REWRITE, ToolId.ASK))
    @Test fun `image result handoffs select Summary and Translate`() = verifyHandoffs(ToolId.IMAGE, listOf("Summarize", "Translate"), listOf(ToolId.SUMMARIZE, ToolId.TRANSLATE))
    @Test fun `transcript handoffs select all advertised text tools`() = verifyHandoffs(ToolId.TRANSCRIBE, listOf("Summarize", "Rewrite", "Translate"), listOf(ToolId.SUMMARIZE, ToolId.REWRITE, ToolId.TRANSLATE))

    private fun verifyHandoffs(tool: ToolId, labels: List<String>, expected: List<ToolId>) {
        show(tool)
        labels.forEach { compose.onNode(hasText(it) and hasClickAction()).performScrollTo().performClick() }
        assertThat(handoffs).containsExactlyElementsIn(expected).inOrder()
    }

    private fun verify(tool: ToolId) {
        show(tool)
        val expected = if (tool == ToolId.DEVELOPER) "Valid JSON\n\n{}" else output
        val index = if (tool == ToolId.PROOFREAD) 1 else 0
        fun action(label: String, at: Int = index) {
            val node = compose.onAllNodes(hasText(label) or hasContentDescription(label))[at]
            if (tool != ToolId.ASK) node.performScrollTo()
            node.performClick()
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        action("Copy")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo(expected)
        action("Share")
        val chooser = shadowOf(context).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo(expected)
        action("Save", 0)
        assertThat(saves).isEqualTo(1)
    }

    private fun show(tool: ToolId, unavailableClipboard: Boolean = false, unavailableShare: Boolean = false,
        showOriginal: Boolean = false) = compose.setContent {
        val baseContext = LocalContext.current
        val context = object : ContextWrapper(baseContext) {
            override fun getSystemService(name: String): Any? =
                if (unavailableClipboard && name == Context.CLIPBOARD_SERVICE) null else super.getSystemService(name)
            override fun startActivity(intent: Intent) {
                if (unavailableShare) throw ActivityNotFoundException("Share sheet unavailable")
                super.startActivity(intent)
            }
        }
        CompositionLocalProvider(LocalContext provides context) {
            LocalAiTheme {
                when (tool) {
                    ToolId.ASK -> AskContent(
                        state = AskUiState(messages = listOf(AskMessage(1, AskRole.USER, "Input"), AskMessage(2, AskRole.MODEL, output))),
                        capability = ready, downloadState = idle, verboseErrors = false,
                        onInputChange = {}, onSend = {}, onStop = {}, onRetry = {}, onNewConversation = {},
                        onSave = { assertThat(it).isEqualTo(2L); save() }, onDownload = {}, onRetryCheck = {}, onNavigateUp = {},
                    )
                    ToolId.SUMMARIZE -> SummarizeContent(
                        state = SummarizeUiState(input = "Input", summary = output), capability = ready, downloadState = idle,
                        verboseErrors = false, onInputChange = {}, onLengthChange = {}, onInputTypeChange = {},
                        onSummarize = {}, onSave = save, onClear = {}, onDownload = {}, onRetryCheck = {},
                        onSendTo = handoffs::add, onNavigateUp = {},
                    )
                    ToolId.REWRITE -> RewriteContent(
                        state = RewriteUiState(input = "Input", rewritten = output, comparedOriginal = "Original text",
                            showOriginal = showOriginal), capability = ready, downloadState = idle,
                        verboseErrors = false, onInputChange = {}, onStyleChange = {}, onRewrite = {}, onAccept = {},
                        onToggleComparison = {}, onSave = save, onClear = {}, onDownload = {}, onRetryCheck = {},
                        onSendTo = handoffs::add, onNavigateUp = {},
                    )
                    ToolId.PROOFREAD -> ProofreadContent(
                        state = ProofreadUiState(input = "Input", comparedOriginal = "Original text", corrected = output),
                        capability = ready, downloadState = idle, verboseErrors = false, onInputChange = {},
                        onInputTypeChange = {}, onProofread = {}, onApply = {}, onSave = save, onClear = {},
                        onDownload = {}, onRetryCheck = {}, onSendTo = handoffs::add, onNavigateUp = {},
                    )
                    ToolId.OCR -> OcrContent(
                        state = OcrUiState(imageUri = android.net.Uri.parse("content://test/image"), extractedText = output,
                            preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)),
                        verboseErrors = false, onImageSelected = {}, onRetry = {}, onClear = {}, onSave = save,
                        onSendTo = handoffs::add, onNavigateUp = {},
                    )
                    ToolId.TRANSLATE -> TranslateContent(
                        state = TranslateUiState(input = "Input", output = output, downloadedLanguages = setOf("en", "es")),
                        verboseErrors = false, onInputChange = {}, onSourceChange = {}, onTargetChange = {}, onSwap = {},
                        onTranslate = {}, onDownloadLanguage = {}, onAcceptDetected = {}, onSave = save, onClear = {}, onNavigateUp = {},
                    )
                    ToolId.IMAGE -> ImageContent(
                        state = ImageUiState(output = output, preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)),
                        capability = ready, downloadState = idle, verboseErrors = false, onImageSelected = {},
                        onModeChange = {}, onQuestionChange = {}, onRun = {}, onStop = {}, onSave = save, onClear = {},
                        onDownload = {}, onRetryCheck = {}, onRunOcr = {}, onSendTextTo = handoffs::add, onNavigateUp = {},
                    )
                    ToolId.TRANSCRIBE -> TranscriptionContent(
                        state = TranscriptionUiState(finalText = output, basicStatus = AiCapabilityStatus.AVAILABLE),
                        verboseErrors = false, onModeChange = {}, onStartRecording = {}, onStopRecording = {},
                        onFileSelected = {}, onMicrophoneDenied = {}, onDownload = {}, onRetryCheck = {}, onSave = save,
                        onClear = {}, onSendTo = handoffs::add, onNavigateUp = {},
                    )
                    ToolId.DEVELOPER -> DeveloperToolContent(
                        tool = DeveloperTool.JSON_VALIDATOR,
                        state = DeveloperUiState(input = "{}", output = "${DeveloperViewModel.VALID_JSON_MARKER}\n\n{}"),
                        capability = ready, downloadState = idle, verboseErrors = false, onInputChange = {}, onRun = {},
                        onClear = {}, onSave = save, onErrorLanguageChange = {}, onBase64DirectionChange = {},
                        onUrlDirectionChange = {}, onHashAlgorithmChange = {}, onUuidCountChange = {}, onUseNow = {},
                        onDownload = {}, onRetryCheck = {}, onNavigateUp = {},
                    )
                }
            }
        }
    }
}
