package com.localai.toolkit.core.ui

import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.navigation.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingShareNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val pending = mutableStateOf(false)
    private val graphReady = mutableStateOf(true)
    private lateinit var nav: NavHostController

    private fun content() {
        compose.setContent {
            nav = rememberNavController()
            // Match LocalAiApp's subcomposed Scaffold: navigation must work even
            // though the NavHost is installed from the content measure pass.
            Scaffold { padding ->
                Box(Modifier.padding(padding)) {
                    if (graphReady.value) NavHost(nav, startDestination = Destination.HOME) {
                        composable(Destination.HOME) { Text("Home marker") }
                        composable(Destination.ASK) { Text("Ask marker") }
                        composable(Destination.SHARE_ROUTER) { Text("Share marker") }
                    }
                }
            }
            HandleIncomingShareNavigation(nav, pending.value)
        }
    }

    @Test fun `pending share waits for graph installation and opens only once`() {
        graphReady.value = false
        pending.value = true
        content()
        compose.runOnIdle { assertThat(nav.currentDestination).isNull() }
        compose.runOnIdle { graphReady.value = true }
        compose.onNodeWithText("Share marker").assertIsDisplayed()
        compose.runOnIdle {
            pending.value = false
            assertThat(nav.popBackStack()).isTrue()
        }
        compose.onNodeWithText("Home marker").assertIsDisplayed()
    }

    @Test fun `cleared pending share never navigates when graph becomes ready`() {
        graphReady.value = false
        pending.value = true
        content()
        compose.runOnIdle { pending.value = false }
        compose.runOnIdle { graphReady.value = true }
        compose.onNodeWithText("Home marker").assertIsDisplayed()
        compose.runOnIdle { assertThat(nav.previousBackStackEntry).isNull() }
    }

    @Test fun `warm share opens router above current tool and cancel can return there`() {
        content()
        compose.runOnIdle { nav.navigate(Destination.ASK) }
        compose.runOnIdle { pending.value = true }
        compose.onNodeWithText("Share marker").assertIsDisplayed()
        compose.runOnIdle {
            pending.value = false
            assertThat(nav.popBackStack()).isTrue()
        }
        compose.onNodeWithText("Ask marker").assertIsDisplayed()
    }

    @Test fun `cold share retains a home back destination and repeated pending state does not duplicate router`() {
        pending.value = true
        content()
        compose.onNodeWithText("Share marker").assertIsDisplayed()
        compose.runOnIdle { pending.value = false }
        compose.runOnIdle { pending.value = true }
        compose.runOnIdle {
            pending.value = false
            nav.popBackStack()
        }
        compose.onNodeWithText("Home marker").assertIsDisplayed()
    }
}
