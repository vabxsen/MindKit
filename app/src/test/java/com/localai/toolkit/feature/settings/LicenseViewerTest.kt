package com.localai.toolkit.feature.settings

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LicenseViewerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `packaged notices contain dependency entries with valid byte ranges`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metadata = context.resources.openRawResource(R.raw.third_party_license_metadata)
            .bufferedReader().use { it.readLines() }
        val notices = context.resources.openRawResource(R.raw.third_party_licenses).use { it.readBytes() }
        assertThat(metadata.size).isGreaterThan(100)
        assertThat(metadata.any { "Activity Compose" in it }).isTrue()
        assertThat(metadata.any { "Compose Material3" in it }).isTrue()
        assertThat(metadata.none { "Debug License Info" in it }).isTrue()
        metadata.filter { it.isNotBlank() }.forEach { entry ->
            val range = entry.substringBefore(' ').split(':').map(String::toInt)
            assertThat(range[0]).isAtLeast(0)
            assertThat(range[1]).isGreaterThan(0)
            assertThat(range[0] + range[1]).isAtMost(notices.size)
            assertThat(notices.copyOfRange(range[0], range[0] + range[1]).toString(Charsets.UTF_8).isNotBlank()).isTrue()
        }
    }

    @Test fun `actual license activity displays the generated library list and opens an entry`() {
        ActivityScenario.launch(OssLicensesMenuActivity::class.java).use { scenario ->
            compose.onNodeWithText("Open source licenses").assertIsDisplayed()
            // The SDK includes the metadata line ending in the accessible row label.
            compose.onNodeWithText("Activity Compose", substring = true).performClick()
            compose.onNodeWithText("Activity Compose", substring = true).assertIsDisplayed()
            compose.onNodeWithText("http://www.apache.org/licenses/LICENSE-2.0.txt", substring = true).assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Open source licenses").assertIsDisplayed()
        }
    }
}
