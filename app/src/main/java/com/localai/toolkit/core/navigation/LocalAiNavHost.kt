package com.localai.toolkit.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.localai.toolkit.core.designsystem.theme.LocalReducedMotion
import com.localai.toolkit.core.designsystem.theme.Motion
import com.localai.toolkit.feature.ask.AskScreen
import com.localai.toolkit.feature.capability.DeviceAiScreen
import com.localai.toolkit.feature.developer.DeveloperListScreen
import com.localai.toolkit.feature.developer.DeveloperTool
import com.localai.toolkit.feature.developer.DeveloperToolScreen
import com.localai.toolkit.feature.history.HistoryDetailScreen
import com.localai.toolkit.feature.history.HistoryScreen
import com.localai.toolkit.feature.image.ImageScreen
import com.localai.toolkit.feature.home.HomeScreen
import com.localai.toolkit.feature.ocr.OcrScreen
import com.localai.toolkit.feature.onboarding.OnboardingScreen
import com.localai.toolkit.feature.proofread.ProofreadScreen
import com.localai.toolkit.feature.rewrite.RewriteScreen
import com.localai.toolkit.feature.settings.AboutScreen
import com.localai.toolkit.feature.settings.ModelsScreen
import com.localai.toolkit.feature.settings.PrivacyScreen
import com.localai.toolkit.feature.settings.SettingsScreen
import com.localai.toolkit.feature.share.ShareRouterScreen
import com.localai.toolkit.feature.summarize.SummarizeScreen
import com.localai.toolkit.feature.transcription.TranscriptionScreen
import com.localai.toolkit.feature.translate.TranslateScreen

/**
 * The app's navigation graph.
 *
 * Detail routes slide in horizontally; top-level tabs cross-fade. Both collapse to an
 * instant change when the user has reduced motion turned on.
 */
@Composable
fun LocalAiNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val duration = if (reducedMotion) 0 else Motion.DURATION_MEDIUM

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(duration, easing = Motion.Standard),
            ) + fadeIn(tween(duration))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(duration, easing = Motion.Standard),
            ) + fadeOut(tween(duration))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(duration, easing = Motion.Standard),
            ) + fadeIn(tween(duration))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(duration, easing = Motion.Standard),
            ) + fadeOut(tween(duration))
        },
    ) {
        composable(Destination.SHARE_ROUTER) {
            ShareRouterScreen(
                onOpenTool = { tool ->
                    navController.navigate(ToolCatalog[tool].route) {
                        // The router has done its job; leaving it on the stack would send
                        // the user back to a share they already acted on.
                        popUpTo(Destination.SHARE_ROUTER) { inclusive = true }
                    }
                },
                onCancel = {
                    navController.navigate(Destination.HOME) {
                        popUpTo(Destination.SHARE_ROUTER) { inclusive = true }
                    }
                },
            )
        }

        composable(Destination.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Destination.HOME) {
                        popUpTo(Destination.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Destination.HOME) {
            HomeScreen(
                onOpenTool = { route -> navController.navigate(route) },
                onOpenHistoryItem = { id ->
                    navController.navigate(Destination.historyDetail(id))
                },
            )
        }

        composable(Destination.HISTORY) {
            HistoryScreen(
                onOpenItem = { id -> navController.navigate(Destination.historyDetail(id)) },
            )
        }

        composable(
            route = Destination.HISTORY_DETAIL,
            arguments = listOf(navArgument(Destination.HISTORY_ID_ARG) { type = NavType.LongType }),
        ) {
            HistoryDetailScreen(onNavigateUp = { navController.navigateUp() })
        }

        composable(Destination.SETTINGS) {
            SettingsScreen(onNavigate = { route -> navController.navigate(route) })
        }

        composable(Destination.DEVICE_AI) {
            DeviceAiScreen(onNavigateUp = { navController.navigateUp() })
        }

        composable(Destination.PRIVACY) {
            PrivacyScreen(onNavigateUp = { navController.navigateUp() })
        }

        composable(Destination.ABOUT) {
            AboutScreen(
                onNavigateUp = { navController.navigateUp() },
                onNavigate = { route -> navController.navigate(route) },
            )
        }

        // Tools. Every card on Home routes to a real screen.
        composable(Destination.MODELS) {
            ModelsScreen(onNavigateUp = { navController.navigateUp() })
        }

        composable(Destination.OCR) {
            OcrScreen(
                onNavigateUp = { navController.navigateUp() },
                onOpenTool = { tool -> navController.navigate(ToolCatalog[tool].route) },
            )
        }

        composable(Destination.TRANSLATE) {
            TranslateScreen(onNavigateUp = { navController.navigateUp() })
        }

        composable(Destination.ASK) {
            AskScreen(onNavigateUp = { navController.navigateUp() })
        }

        composable(Destination.SUMMARIZE) {
            SummarizeScreen(
                onNavigateUp = { navController.navigateUp() },
                onOpenTool = { tool -> navController.navigate(ToolCatalog[tool].route) },
            )
        }

        composable(Destination.REWRITE) {
            RewriteScreen(
                onNavigateUp = { navController.navigateUp() },
                onOpenTool = { tool -> navController.navigate(ToolCatalog[tool].route) },
            )
        }

        composable(Destination.PROOFREAD) {
            ProofreadScreen(
                onNavigateUp = { navController.navigateUp() },
                onOpenTool = { tool -> navController.navigate(ToolCatalog[tool].route) },
            )
        }

        composable(Destination.IMAGE) {
            ImageScreen(
                onNavigateUp = { navController.navigateUp() },
                onOpenTool = { tool -> navController.navigate(ToolCatalog[tool].route) },
            )
        }

        composable(Destination.TRANSCRIBE) {
            TranscriptionScreen(
                onNavigateUp = { navController.navigateUp() },
                onOpenTool = { tool -> navController.navigate(ToolCatalog[tool].route) },
            )
        }

        composable(Destination.DEVELOPER) {
            DeveloperListScreen(
                onOpenTool = { tool -> navController.navigate(Destination.developerTool(tool.name)) },
                onNavigateUp = { navController.navigateUp() },
            )
        }

        composable(
            route = Destination.DEVELOPER_TOOL,
            arguments = listOf(navArgument(Destination.DEVELOPER_TOOL_ARG) { type = NavType.StringType }),
        ) { entry ->
            // An unrecognised name can only come from a malformed deep link; falling back
            // to the JSON formatter is better than crashing on valueOf.
            val tool = entry.arguments
                ?.getString(Destination.DEVELOPER_TOOL_ARG)
                ?.let { name -> DeveloperTool.entries.firstOrNull { it.name == name } }
                ?: DeveloperTool.JSON_FORMATTER
            DeveloperToolScreen(tool = tool, onNavigateUp = { navController.navigateUp() })
        }

    }
}
