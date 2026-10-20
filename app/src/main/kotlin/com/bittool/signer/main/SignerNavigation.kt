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
 * Tab and Wallets-stack navigation over the one NavHost (ARCHITECTURE §2.2, §2.4): each tab keeps its own stack (saved
 * when another tab is shown, restored when it comes back); the router's Wallets requests are applied here. Main
 * thread, like the NavController.
 */
class ShellNavigator(val navController: NavHostController) {
    /**
     * A `Reset` that arrived while another tab was shown: Wallets' saved stack is popped to its root the next time it is
     * shown. (Dropping the saved stack with `clearBackStack` instead leaves navigation unable to restore Wallets, the
     * start graph, ever again: the restoring navigation silently does nothing.)
     */
    private var walletsResetPending = false

    /** The tab whose page is on screen, or null on a root-level page. */
    val shownTab: Tab? get() = navController.currentBackStackEntry?.destination?.tab()

    /**
     * The tab the shown page belongs to: [shownTab], or for a root-level page ("Submit a Wallet", pushed from Connect or
     * Compatibility) the tab it was opened from, i.e. the nearest page below it that lies in a tab graph. That tab stays
     * selected in the bar, so a tap on it is a reselection (iOS pops the stack the page was pushed onto).
     */
    val selectedTab: Tab?
        get() = navController.currentBackStack.value.asReversed().firstNotNullOfOrNull { it.destination.tab() }

    /** The page on screen is the root of Scan, Activity or Settings: system Back selects Wallets (ARCHITECTURE §2.3). */
    val isAtOtherTabRoot: Boolean
        get() {
            val destination = navController.currentBackStackEntry?.destination ?: return false
            return Tab.entries.any { it != Tab.Wallets && destination.hasRoute(it.root::class) }
        }

    /** Shows [tab] with its own stack; nothing when it already shows. */
    fun showTab(tab: Tab) {
        if (shownTab != tab) {
            navController.navigate(tab.graph) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        if (tab == Tab.Wallets && walletsResetPending && shownTab == Tab.Wallets) {
            walletsResetPending = false
            navController.popBackStack(WalletsRoute, inclusive = false)
        }
    }

    /**
     * Reselecting the selected tab: back to its root (the root screen also scrolls to the top on
     * `AppRouter.reselections`), also from a root-level page opened from that tab.
     */
    fun popToRoot(tab: Tab) {
        if (selectedTab != tab) return
        navController.popBackStack(tab.root, inclusive = false)
    }

    /**
     * System Back on the root of Scan, Activity or Settings: the Wallets tab, exactly as a tap on it, so Wallets comes
     * back with the stack it was left with. (Letting the NavHost pop the tab graph instead shows Wallets' root while its
     * saved stack stays behind, to reappear on a later tab switch.) Returns whether it handled Back.
     */
    fun backFromTabRoot(): Boolean {
        if (!isAtOtherTabRoot) return false
        showTab(Tab.Wallets)
        return true
    }

    /**
     * One Wallets-stack change (iOS `walletPath` assignments):
     * - `Replace(route)`: the Wallets tab, then [route] as the only page above `WalletsRoute`;
     * - `Reset`: back to `WalletsRoute` when Wallets is showing, otherwise when it is shown next. Other tabs' stacks are
     *   untouched.
     */
    fun execute(request: WalletStackRequest) {
        when (request) {
            is WalletStackRequest.Replace -> {
                showTab(Tab.Wallets)
                walletsResetPending = false
                navController.navigate(request.route) {
                    popUpTo<WalletsRoute>()
                    launchSingleTop = true
                }
            }
            WalletStackRequest.Reset -> {
                if (shownTab == Tab.Wallets) {
                    walletsResetPending = false
                    navController.popBackStack(WalletsRoute, inclusive = false)
                } else {
                    walletsResetPending = true
                }
            }
        }
    }
}

/**
 * The `AppRouter` ↔ NavHost glue (ARCHITECTURE §2.2–§2.4): the selected tab is shown, a reselection pops to the root,
 * Wallets-stack requests run in order, and the shown page's tab is reported back (system Back to Wallets, a restored
 * stack). Compose this after the NavHost (its graph must exist).
 */
@Composable
fun RouterGlue(navigator: ShellNavigator, router: AppRouter) {
    val navController = navigator.navController
    LaunchedEffect(navigator, router) {
        router.tab.collect(navigator::showTab)
    }
    LaunchedEffect(navigator, router) {
        router.reselections.collect(navigator::popToRoot)
    }
    LaunchedEffect(navigator, router) {
        router.walletStackRequests.collect(navigator::execute)
    }
    DisposableEffect(navController, router) {
        // The controller's current page, not the dispatched one: a restore replays entries through the listener.
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            controller.currentDestination?.tab()?.let(router::onTabShown)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
}
