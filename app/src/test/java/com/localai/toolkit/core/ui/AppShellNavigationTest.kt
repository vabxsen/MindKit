package com.localai.toolkit.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TextField
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.designsystem.theme.LocalAiTheme
import com.localai.toolkit.core.navigation.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppShellNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController

    private fun content(start: String = Destination.HOME, fontScale: Float? = null) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
            ) {
                LocalAiTheme {
                    LocalAiAppShell(startDestination = start) { controller, destination ->
                        nav = controller
                        NavHost(navController = controller, startDestination = destination) {
                            composable(Destination.HOME) { Text("Home marker") }
                            composable(Destination.SETTINGS) { Text("Settings marker") }
                            composable(Destination.HISTORY) {
                                val input = rememberSaveable { mutableStateOf("") }
                                TextField(value = input.value, onValueChange = { input.value = it }, label = { Text("History search") })
                            }
                            composable(Destination.ASK) { Text("Tool marker") }
                            composable(Destination.ONBOARDING) { Text("Setup marker") }
                        }
                    }
                }
            }
        }
    }

    @Test fun `extra large text uses a short readable Home tab label`() {
        content(fontScale = 2f)

        compose.onNodeWithText("Home").assertIsDisplayed().performClick()
        compose.onNodeWithText("Workspace").assertDoesNotExist()
    }

    @Test fun `tab switching restores history input without growing the back stack`() {
        content()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("History search").performTextInput("meeting")
        repeat(3) {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("History").performClick()
        }
        compose.onNodeWithText("meeting").assertExists()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Home marker").assertIsDisplayed()
        compose.runOnIdle { assertThat(nav.previousBackStackEntry).isNull() }
    }

    @Test fun `finishing onboarding establishes Home as the tab root`() {
        content(Destination.ONBOARDING)
        compose.onNodeWithText("Workspace").assertDoesNotExist()
        compose.runOnIdle {
            nav.navigate(Destination.HOME) { popUpTo(Destination.ONBOARDING) { inclusive = true } }
        }
        repeat(3) {
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Settings").performClick()
        }
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Home marker").assertIsDisplayed()
        compose.runOnIdle { assertThat(nav.previousBackStackEntry).isNull() }
    }

    @Test fun `tool detail hides bottom tabs and Back restores them`() {
        content()
        compose.runOnIdle { nav.navigate(Destination.ASK) }
        compose.onNodeWithText("Tool marker").assertIsDisplayed()
        compose.onNodeWithText("Workspace").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Workspace").assertIsDisplayed()
    }
}
