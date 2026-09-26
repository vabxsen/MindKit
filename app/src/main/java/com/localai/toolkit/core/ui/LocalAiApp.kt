package com.localai.toolkit.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localai.toolkit.R
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.core.navigation.LocalAiNavHost
import com.localai.toolkit.core.designsystem.theme.FieldNotesTomato
import com.localai.toolkit.core.designsystem.theme.FieldNotesTomatoLight
import kotlinx.coroutines.flow.first

private data class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

private val bottomNavItems = listOf(
    BottomNavItem(Destination.HOME, Icons.Outlined.SpaceDashboard, R.string.nav_home),
    BottomNavItem(Destination.HISTORY, Icons.Outlined.History, R.string.nav_history),
    BottomNavItem(Destination.SETTINGS, Icons.Outlined.Settings, R.string.nav_settings),
)

/**
 * The app shell: bottom navigation plus the navigation host.
 *
 * The bar is only shown on the three top-level destinations. Tool screens are detail
 * routes and take the full height, which keeps a long text editor or a chat thread from
 * fighting with the navigation bar for space.
 */
@Composable
fun LocalAiApp(
    startDestination: String,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    hasPendingShare: Boolean = false,
) {
    LocalAiAppShell(startDestination, modifier, navController, hasPendingShare) { controller, start ->
        LocalAiNavHost(navController = controller, startDestination = start)
    }
}

/** The real tab/navigation shell; the graph is injectable for isolated navigation tests. */
@Composable
internal fun LocalAiAppShell(
    startDestination: String,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    hasPendingShare: Boolean = false,
    graph: @Composable (NavHostController, String) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = currentDestination?.route in Destination.topLevelRoutes

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                androidx.compose.foundation.layout.Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                    ) {
                        bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == item.route } == true
                        val label = stringResource(
                            if (item.route == Destination.HOME && LocalDensity.current.fontScale >= 1.6f) {
                                R.string.nav_home_compact
                            } else item.labelRes,
                        )
                        NavigationBarItem(
                            selected = selected,
                            modifier = Modifier.testTag("bottom-nav-${item.route}"),
                            onClick = {
                                navController.navigate(item.route) {
                                    // Single-top with state preservation: switching tabs
                                    // returns to where the user was, and does not grow
                                    // the back stack.
                                    // Home remains the tab root after onboarding has
                                    // been removed from the back stack.
                                    popUpTo(Destination.HOME) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = FieldNotesTomato,
                                selectedTextColor = FieldNotesTomato,
                                indicatorColor = FieldNotesTomatoLight,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            graph(navController, startDestination)
        }
    }
    HandleIncomingShareNavigation(navController, hasPendingShare)
}

/** Shared by cold and warm starts; the router is never duplicated on restoration. */
@Composable
internal fun HandleIncomingShareNavigation(navController: NavHostController, hasPendingShare: Boolean) {
    LaunchedEffect(navController, hasPendingShare) {
        if (!hasPendingShare) return@LaunchedEffect
        // Scaffold installs the NavHost during subcomposition. On recreation,
        // this effect can start first; wait for the restored graph's entry.
        // Clearing the pending share cancels this wait with the effect.
        navController.currentBackStackEntryFlow.first()
        if (navController.currentDestination?.route != Destination.SHARE_ROUTER) {
            navController.navigate(Destination.SHARE_ROUTER) { launchSingleTop = true }
        }
    }
}
