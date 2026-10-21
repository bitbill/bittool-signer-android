package com.bittool.signer.feature.sign

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.navigation.ScanRoute
import com.bittool.signer.core.navigation.ScannerRoute
import com.bittool.signer.feature.sign.scanner.SigningScannerScreen
import com.bittool.signer.feature.sign.tab.ScanTabScreen

/**
 * The Scan tab inside `navigation<ScanGraph>(startDestination = ScanRoute)` (ARCHITECTURE §2.2):
 *
 * - [ScanRoute]: Home, Review or Signature chosen from `SignerState` ([ScanTabScreen]); the paste sheet, the file
 *   picker, the "Discard partial signatures?" dialog, the authorize sheet and the export picker are hosted there.
 *
 * The camera itself is [scannerDestination], registered at the root.
 */
fun NavGraphBuilder.scanDestinations(navController: NavController) {
    composable<ScanRoute>(
        // The tab stays in place under the camera sliding over it.
        exitTransition = { if (targetState.destination.hasRoute<ScannerRoute>()) ExitTransition.KeepUntilTransitionsFinished else null },
        popEnterTransition = { if (initialState.destination.hasRoute<ScannerRoute>()) EnterTransition.None else null },
    ) {
        ScanTabScreen(
            onOpenScanner = {
                if (navController.currentDestination?.hasRoute<ScanRoute>() == true) {
                    navController.navigate(ScannerRoute) { launchSingleTop = true }
                }
            },
        )
    }
}

/**
 * [ScannerRoute]: the full-screen camera, a root-level modal route without the tab bar that slides up over the page that
 * opened it (iOS `fullScreenCover`), the Scan home or the Wallets toolbar, and back down when closed, by its cross, system
 * Back or a completed request. Registered outside the tab graphs, so the Wallets toolbar opens it right over Wallets: a
 * cancelled scan leaves Wallets where it was, and a request that arrives selects Scan, where its review is.
 */
fun NavGraphBuilder.scannerDestination(navController: NavController) {
    composable<ScannerRoute>(
        enterTransition = { slideInVertically(SignerMotion.smooth(0.35)) { it } },
        exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { slideOutVertically(tween(durationMillis = 300)) { it } },
    ) {
        SigningScannerScreen(
            onClose = {
                if (navController.currentDestination?.hasRoute<ScannerRoute>() == true) navController.popBackStack()
            },
        )
    }
}
