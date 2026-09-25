package com.localai.toolkit.feature

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import java.io.FileInputStream
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.feature.ocr.OcrContent
import com.localai.toolkit.feature.ocr.OcrUiState
import com.localai.toolkit.feature.transcription.TranscriptionContent
import com.localai.toolkit.feature.transcription.TranscriptionUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real system pickers and cancellation callbacks, not activity-result stubs.
 * Does not claim successful URI grants or hardware recognition.
 */
@RunWith(AndroidJUnit4::class)
class SystemPickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun photoPickerCancelReturnsWithoutSelectingAnImage() {
        var selections = 0
        compose.setContent { LocalAiTheme {
            OcrContent(OcrUiState(), false, { selections++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Choose a photo").performScrollTo().performClick()
        closeExternalPicker(ActivityResultContracts.PickVisualMedia().createIntent(
            instrumentation.targetContext, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)))
        compose.onNodeWithText("Choose a photo").assertIsDisplayed()
        compose.runOnIdle { assertThat(selections).isEqualTo(0) }
    }

    @Test fun audioDocumentPickerCancelReturnsWithoutStartingTranscription() {
        var selections = 0
        compose.setContent { LocalAiTheme {
            TranscriptionContent(
                TranscriptionUiState(basicStatus = AiCapabilityStatus.AVAILABLE,
                    provider = AiProvider.ML_KIT, supportsFileInput = true),
                false, {}, {}, {}, { selections++ }, {}, {}, {}, {}, {}, {}, {},
            )
        } }
        compose.onNodeWithText("Choose an audio file").performScrollTo().performClick()
        closeExternalPicker(ActivityResultContracts.OpenDocument().createIntent(
            instrumentation.targetContext, arrayOf("audio/*")))
        compose.onNodeWithText("Choose an audio file").assertIsDisplayed()
        compose.runOnIdle { assertThat(selections).isEqualTo(0) }
    }

    private fun closeExternalPicker(intent: Intent) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // App queries can return the media-module proxy (or nothing); Android's
        // shell resolver sees the currently selected modular system picker.
        // Both action and MIME type come from the fixed contracts above, not user input.
        val resolution = automation.executeShellCommand(
            "cmd package resolve-activity --brief -a ${intent.action} -t ${intent.type}",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() } }
        val component = checkNotNull(resolution.lineSequence().map(String::trim)
            .firstOrNull { it.contains('/') && !it.contains(' ') }) { resolution }
        val expectedPackage = component.substringBefore('/')
        assertThat(expectedPackage).isNotEqualTo(instrumentation.targetContext.packageName)
        val observations = mutableSetOf<String>()
        compose.waitUntil(20_000) {
            val packages = (automation.windows.mapNotNull { it.root?.packageName?.toString() } +
                listOfNotNull(automation.rootInActiveWindow?.packageName?.toString())).toSet()
            if (observations.add(packages.toString())) Log.i("MindKitPickerTest", "Expected $expectedPackage; windows $packages")
            expectedPackage in packages
        }
        assertThat(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)).isTrue()
        compose.waitUntil(20_000) {
            automation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName
        }
    }
}
