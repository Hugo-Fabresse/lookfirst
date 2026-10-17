package com.lookfirst.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lookfirst.data.scan.camera.QrAnalyzerFactory
import com.lookfirst.ui.screens.result.ResultRoute
import com.lookfirst.ui.screens.result.ResultViewModel
import com.lookfirst.ui.screens.scan.ScanRoute
import com.lookfirst.ui.screens.scan.ScanViewModel

private const val ROUTE_SCAN = "scan"
private const val ROUTE_RESULT = "result"

/**
 * Scan -> Result -> (Cancel or back) Scan. The scanned text never travels in a route: the Result view model
 * receives it directly, so it is not in the back stack, in saved state or in any log. No transition animation:
 * the screen changes at once, which also respects reduced motion.
 */
@Composable
fun LookFirstNavHost(
    scanViewModel: ScanViewModel,
    resultViewModel: ResultViewModel,
    analyzerFactory: QrAnalyzerFactory,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = ROUTE_SCAN,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(ROUTE_SCAN) {
            ScanRoute(
                viewModel = scanViewModel,
                analyzerFactory = analyzerFactory,
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
    }
}
