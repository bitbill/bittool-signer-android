package com.bittool.signer.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bittool.signer.core.designsystem.icon.SignerIcons

import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.core.navigation.WalletsGraph
import com.bittool.signer.core.ui.text.currentLocale
import com.bittool.signer.core.ui.text.formatInteger

/**
 * The four tabs (iOS `MainTabView`, Features/Main/MainTabView.swift; app-shell.md §8, §15.10; ARCHITECTURE §2.2):
 * Wallets, Scan (badge "1" while a request waits unsigned), Activity, Settings, in that order, over the one NavHost.
 *
 * - A Material 3 navigation bar on compact widths and a navigation rail from 600 dp (tablets, landscape: the iPad
 *   layout). "The tab bar stays expanded": no hide-on-scroll. It slides away on the modal routes and while the keyboard
 *   is up (iOS keeps it under the keyboard), without fading.
 * - A tap goes through `AppRouter.onTabClicked`: another tab is shown with its own stack restored; the selected tab
 *   pops to its root (and its screen scrolls to the top).
 * - The bar's own insets (navigation bar / cutout) are consumed for the pages above it, so their bottom bars do not pad
 *   twice; with the keyboard up the pages get the full IME inset.
 *
 * [destinations] is the graph (the features' entry points in the app; stand-ins in tests).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScaffold(
    router: AppRouter,
    showsScanBadge: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    destinations: NavGraphBuilder.(NavController) -> Unit = { signerDestinations(it) },
) {
    val entry by navController.currentBackStackEntryAsState()
    val routerTab by router.tab.collectAsState()
    val destination = entry?.destination
    val selected = destination?.tab() ?: routerTab
    val showBar = destination?.isModal() != true && !WindowInsets.isImeVisible
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val transitions = remember(rtl) { NavTransitions(rtl) }
    val navigator = remember(navController) { ShellNavigator(navController) }
    // A tap is judged against the tab that is actually shown: the shown tab is a reselection (pop to its root), any
    // other is shown at once (the router follows; its collector then finds nothing left to do).
    val onTab: (Tab) -> Unit = remember(navigator, router) {
        { tab ->
            if (navigator.shownTab == tab) {
                router.onTabShown(tab)
                router.onTabClicked(tab)
            } else {
                router.selectTab(tab)
                navigator.showTab(tab)
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(MainTags.ROOT)) {
        val rail = maxWidth >= RailMinWidth
        val host: @Composable (Modifier) -> Unit = { hostModifier ->
            NavHost(
                navController = navController,
                startDestination = WalletsGraph,
                modifier = hostModifier,
                enterTransition = { transitions.enter(this) },
                exitTransition = { transitions.exit(this) },
                popEnterTransition = { transitions.popEnter(this) },
                popExitTransition = { transitions.popExit(this) },
            ) {
                destinations(navController)
            }
        }
        if (rail) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = showBar,
                    enter = slideInHorizontally(SignerMotion.smooth<IntOffset>(BAR_SECONDS)) { if (rtl) it else -it } +
                        expandHorizontally(SignerMotion.smooth<IntSize>(BAR_SECONDS)),
                    exit = slideOutHorizontally(SignerMotion.smooth<IntOffset>(BAR_SECONDS)) { if (rtl) it else -it } +
                        shrinkHorizontally(SignerMotion.smooth<IntSize>(BAR_SECONDS)),
                ) {
                    SignerNavigationRail(selected, showsScanBadge, onTab)
                }
                val consumed = if (showBar) WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start) else WindowInsets(0)
                host(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(consumed))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                val consumed = if (showBar) WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) else WindowInsets(0)
                host(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(consumed))
                AnimatedVisibility(
                    visible = showBar,
                    enter = slideInVertically(SignerMotion.smooth<IntOffset>(BAR_SECONDS)) { it } +
                        expandVertically(SignerMotion.smooth<IntSize>(BAR_SECONDS), expandFrom = Alignment.Top),
                    exit = slideOutVertically(SignerMotion.smooth<IntOffset>(BAR_SECONDS)) { it } +
                        shrinkVertically(SignerMotion.smooth<IntSize>(BAR_SECONDS), shrinkTowards = Alignment.Top),
                ) {
                    SignerNavigationBar(selected, showsScanBadge, onTab)
                }
            }
        }
        RouterGlue(navigator, router)
    }
}

/** The bottom bar: the page's card colour under a hairline, accent-tinted selection pill. */
@Composable
private fun SignerNavigationBar(selected: Tab, showsScanBadge: Boolean, onTab: (Tab) -> Unit) {
    val colors = SignerTheme.colors
    val waiting = stringResource(R.string.shell_scan_badge_description)
    Column {
        Spacer(Modifier.fillMaxWidth().height(Hairline).background(colors.separator))
        NavigationBar(containerColor = colors.card, contentColor = colors.label, tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onTab(tab) },
                    icon = { TabIcon(tab, showsScanBadge) },
                    label = { TabLabel(tab, isSelected) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accentOnTint,
                        selectedTextColor = colors.accentOnTint,
                        indicatorColor = colors.accent.copy(alpha = 0.14f),
                        unselectedIconColor = colors.secondaryLabel,
                        unselectedTextColor = colors.secondaryLabel,
                    ),
                    modifier = tabModifier(tab, showsScanBadge, waiting),
                )
            }
        }
    }
}

/** The rail for wide windows: the same items, centred vertically. */
@Composable
private fun SignerNavigationRail(selected: Tab, showsScanBadge: Boolean, onTab: (Tab) -> Unit) {
    val colors = SignerTheme.colors
    val waiting = stringResource(R.string.shell_scan_badge_description)
    Row(Modifier.fillMaxHeight()) {
        NavigationRail(containerColor = colors.card, contentColor = colors.label) {
            Spacer(Modifier.weight(1f))
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                NavigationRailItem(
                    selected = isSelected,
                    onClick = { onTab(tab) },
                    icon = { TabIcon(tab, showsScanBadge) },
                    label = { TabLabel(tab, isSelected) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = colors.accentOnTint,
                        selectedTextColor = colors.accentOnTint,
                        indicatorColor = colors.accent.copy(alpha = 0.14f),
                        unselectedIconColor = colors.secondaryLabel,
                        unselectedTextColor = colors.secondaryLabel,
                    ),
                    modifier = tabModifier(tab, showsScanBadge, waiting),
                )
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(Hairline).fillMaxHeight().background(colors.separator))
    }
}

@Composable
private fun TabIcon(tab: Tab, showsScanBadge: Boolean) {
    if (tab == Tab.Scan && showsScanBadge) {
        val colors = SignerTheme.colors
        val count = formatInteger(1, currentLocale())
        BadgedBox(badge = { Badge(containerColor = colors.danger, contentColor = Color.White) { Text(count) } }) {
            Icon(tab.icon, contentDescription = null)
        }
    } else {
        Icon(tab.icon, contentDescription = null)
    }
}

@Composable
private fun TabLabel(tab: Tab, selected: Boolean) {
    val typography = SignerTheme.typography
    Text(
        text = stringResource(tab.title),
        style = if (selected) typography.caption.semibold() else typography.caption,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The e2e tag of the tab, and on Scan the waiting request as the item's state ("A signing request is waiting"). */
private fun tabModifier(tab: Tab, showsScanBadge: Boolean, waiting: String): Modifier {
    val tagged = Modifier.testTag(tab.testTag)
    return if (tab == Tab.Scan && showsScanBadge) tagged.semantics { stateDescription = waiting } else tagged
}

private val Tab.title: Int
    get() = when (this) {
        Tab.Wallets -> R.string.wallets
        Tab.Scan -> R.string.scan
        Tab.Activity -> R.string.activity
        Tab.Settings -> R.string.settings
    }

/** iOS SF Symbols `wallet.bifold`, `qrcode.viewfinder`, `clock.arrow.circlepath`, `gearshape`. */
private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.Wallets -> SignerIcons.Wallet
        Tab.Scan -> SignerIcons.QrCodeScanner
        Tab.Activity -> SignerIcons.History
        Tab.Settings -> SignerIcons.Settings
    }

/** `tab.wallets`, `tab.scan`, … (Android-only; the iOS suite finds tabs by their label). */
val Tab.testTag: String get() = "tab." + name.lowercase()


/** Test tags of the tab shell. */
object MainTags {
    const val ROOT = "main"
}

private val RailMinWidth: Dp = 600.dp
private val Hairline: Dp = 0.5.dp
private const val BAR_SECONDS = 0.3
