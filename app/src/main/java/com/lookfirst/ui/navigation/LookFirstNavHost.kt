package com.lookfirst.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lookfirst.data.scan.camera.QrAnalyzerFactory
import com.lookfirst.ui.screens.onboarding.OnboardingScreen
import com.lookfirst.ui.screens.result.ResultRoute
import com.lookfirst.ui.screens.result.ResultViewModel
import com.lookfirst.ui.screens.scan.ScanRoute
import com.lookfirst.ui.screens.scan.ScanViewModel
import com.lookfirst.ui.screens.settings.AboutScreen
import com.lookfirst.ui.screens.settings.SettingsRoute
import com.lookfirst.ui.screens.settings.SettingsViewModel

private const val ROUTE_SCAN = "scan"
private const val ROUTE_RESULT = "result"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ABOUT = "about"
private const val ROUTE_ONBOARDING = "onboarding"

/**
 * First launch (once) -> Scan <-> Settings -> About; Scan -> Result -> (Cancel or back) Scan. The scanned text never travels in a route: the Result view model
 * receives it directly, so it is not in the back stack, in saved state or in any log. No transition animation:
 * the screen changes at once, which also respects reduced motion.
 */
@Composable
fun LookFirstNavHost(
    scanViewModel: ScanViewModel,
    resultViewModel: ResultViewModel,
    settingsViewModel: SettingsViewModel,
    analyzerFactory: QrAnalyzerFactory,
    showFirstLaunch: Boolean,
    onFirstLaunchDone: (autoUpdate: Boolean) -> Unit,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (showFirstLaunch) ROUTE_ONBOARDING else ROUTE_SCAN,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(ROUTE_ONBOARDING) {
            OnboardingScreen(
                onStart = { autoUpdate ->
                    onFirstLaunchDone(autoUpdate)
                    navController.navigate(ROUTE_SCAN) { popUpTo(ROUTE_ONBOARDING) { inclusive = true } }
                },
            )
        }
        composable(ROUTE_SCAN) {
            ScanRoute(
                viewModel = scanViewModel,
                analyzerFactory = analyzerFactory,
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                onCodeRead = { content, multipleCodes ->
                    resultViewModel.show(content, multipleCodes)
                    navController.navigate(ROUTE_RESULT) { launchSingleTop = true }
                },
            )
        }
        composable(ROUTE_RESULT) {
            ResultRoute(
                viewModel = resultViewModel,
                onClose = {
                    resultViewModel.clear()
                    scanViewModel.onResultClosed()
                    if (navController.currentDestination?.route == ROUTE_RESULT) navController.popBackStack()
                },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsRoute(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(ROUTE_ABOUT) { launchSingleTop = true } },
            )
        }
        composable(ROUTE_ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
