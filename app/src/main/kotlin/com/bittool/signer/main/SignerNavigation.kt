package com.bittool.signer.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.navigation
import com.bittool.signer.core.navigation.ActivityGraph
import com.bittool.signer.core.navigation.ActivityRoute
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.ModalRouteClasses
import com.bittool.signer.core.navigation.ScanGraph
import com.bittool.signer.core.navigation.ScanRoute
import com.bittool.signer.core.navigation.SettingsGraph
import com.bittool.signer.core.navigation.SettingsRoute
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.core.navigation.WalletStackRequest
import com.bittool.signer.core.navigation.WalletsGraph
import com.bittool.signer.core.navigation.WalletsRoute
import com.bittool.signer.feature.activity.activityDestinations
import com.bittool.signer.feature.settings.settingsDestinations
import com.bittool.signer.feature.settings.settingsSharedDestinations
import com.bittool.signer.feature.setup.recoveryPhraseDestination
import com.bittool.signer.feature.sign.scanDestinations
import com.bittool.signer.feature.wallets.walletsDestinations

/**
 * The one navigation graph (ARCHITECTURE.md §2.2): a nested graph per tab, every feature registering its own
 * destinations through its frozen entry point, plus the root-level "Submit a Wallet" (it keeps the current tab).
 * Start: the Wallets graph. Not destinations: setup (an overlay), Welcome and onboarding (stages), the lock (a window),
 * sheets and dialogs (hosted by their screens).
 */
fun NavGraphBuilder.signerDestinations(navController: NavController) {
    navigation<WalletsGraph>(startDestination = WalletsRoute) {
        walletsDestinations(navController)
        recoveryPhraseDestination(navController)
    }
    navigation<ScanGraph>(startDestination = ScanRoute) {
        scanDestinations(navController)
    }
    navigation<ActivityGraph>(startDestination = ActivityRoute) {
        activityDestinations(navController)
    }
    navigation<SettingsGraph>(startDestination = SettingsRoute) {
        settingsDestinations(navController)
    }
    settingsSharedDestinations(navController)
}

/** The tab whose graph holds this destination, or null for root-level destinations ("Submit a Wallet"). */
fun NavDestination.tab(): Tab? = hierarchy.firstNotNullOfOrNull { node -> Tab.entries.firstOrNull { node.hasRoute(it.graph::class) } }

/** A destination drawn without the tab bar (iOS sheets and full-screen covers): `ModalRouteClasses`. */
fun NavDestination.isModal(): Boolean = ModalRouteClasses.any { hasRoute(it) }

/**
 * Shows [tab] (ARCHITECTURE §2.2): its graph with its own back stack restored, the other tabs' stacks saved. Nothing
 * when it is already showing.
 */
fun NavHostController.navigateToTab(tab: Tab) {
    if (currentBackStackEntry?.destination?.tab() == tab) return
    navigate(tab.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Reselecting the shown tab: back to its root (the root screen also scrolls to the top on `AppRouter.reselections`). */
fun NavHostController.popToTabRoot(tab: Tab) {
    if (currentBackStackEntry?.destination?.tab() != tab) return
    popBackStack(tab.root, inclusive = false)
}

/**
 * One Wallets-stack change (iOS `walletPath` assignments, ARCHITECTURE §2.4):
 * - `Replace(route)`: the Wallets tab, then [route] as the only page above `WalletsRoute`;
 * - `Reset`: back to `WalletsRoute` when Wallets is showing, otherwise its saved stack is dropped, so the tab opens
 *   on its root next time. Other tabs' stacks are untouched.
 */
fun NavHostController.execute(request: WalletStackRequest) {
    when (request) {
        is WalletStackRequest.Replace -> {
            navigateToTab(Tab.Wallets)
            navigate(request.route) {
                popUpTo<WalletsRoute>()
                launchSingleTop = true
            }
        }
        WalletStackRequest.Reset -> {
            if (currentBackStackEntry?.destination?.tab() == Tab.Wallets) {
                popBackStack(WalletsRoute, inclusive = false)
            } else {
                clearBackStack<WalletsGraph>()
            }
        }
    }
}

/**
 * The `AppRouter` ↔ NavHost glue (ARCHITECTURE §2.2–§2.4): the selected tab is shown, a reselection pops to the root,
 * Wallets-stack requests run in order, and the shown destination's tab is reported back (system Back to Wallets, a
 * restored stack). Compose this after the NavHost (its graph must exist).
 */
@Composable
fun RouterGlue(navController: NavHostController, router: AppRouter) {
    LaunchedEffect(navController, router) {
        router.tab.collect { navController.navigateToTab(it) }
    }
    LaunchedEffect(navController, router) {
        router.reselections.collect { navController.popToTabRoot(it) }
    }
    LaunchedEffect(navController, router) {
        router.walletStackRequests.collect { navController.execute(it) }
    }
    DisposableEffect(navController, router) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            destination.tab()?.let(router::onTabShown)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
}
