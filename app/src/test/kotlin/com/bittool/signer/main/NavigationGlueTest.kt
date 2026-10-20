package com.bittool.signer.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.ActivityGraph
import com.bittool.signer.core.navigation.ActivityRoute
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.ChangePasswordRoute
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.core.navigation.LanguageRoute
import com.bittool.signer.core.navigation.ScanGraph
import com.bittool.signer.core.navigation.ScanRoute
import com.bittool.signer.core.navigation.SettingsGraph
import com.bittool.signer.core.navigation.SettingsRoute
import com.bittool.signer.core.navigation.SubmitWalletRoute
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.navigation.WalletsGraph
import com.bittool.signer.core.navigation.WalletsRoute
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The AppRouter ↔ NavHost glue (ARCHITECTURE.md §2.2–§2.4; iOS AppRouter + MainTabView): tab switching with saved
 * stacks, reselection, `open` (Replace) and `resetNavigation` (Reset) in order, system Back from a tab root, the
 * bar on modal routes, the Scan badge. The features' destinations are stand-ins with the real routes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class NavigationGlueTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val router = AppRouter()
    private lateinit var nav: NavHostController
    private val wallet = WalletId.random()

    private val destinations: NavGraphBuilder.(NavController) -> Unit = {
        navigation<WalletsGraph>(startDestination = WalletsRoute) {
            page<WalletsRoute>("wallets-root")
            page<WalletDetailRoute>("wallet-detail")
            page<ConnectRoute>("connect")
            page<ChangePasswordRoute>("change-password")
        }
        navigation<ScanGraph>(startDestination = ScanRoute) { page<ScanRoute>("scan-root") }
        navigation<ActivityGraph>(startDestination = ActivityRoute) { page<ActivityRoute>("activity-root") }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            page<SettingsRoute>("settings-root")
            page<LanguageRoute>("language")
        }
        page<SubmitWalletRoute>("submit-wallet")
    }

    private inline fun <reified T : Any> NavGraphBuilder.page(tag: String) {
        composable<T> { Text(tag, Modifier.testTag(tag)) }
    }

    private fun show(badge: Boolean = false) {
        compose.setContent {
            nav = rememberNavController()
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                MainScaffold(router = router, showsScanBadge = badge, navController = nav, destinations = destinations)
            }
        }
        compose.waitForIdle()
    }

    private fun onUi(block: () -> Unit) {
        compose.runOnUiThread(block)
        compose.waitForIdle()
    }

    private fun tap(tab: Tab) {
        compose.onNodeWithTag(tab.testTag).performClick()
        compose.waitForIdle()
    }

    /** The pages of the back stack (graphs left out), bottom first. */
    private fun stack(): List<KClass<*>> = nav.currentBackStack.value
        .map { it.destination }
        .filter { it !is NavGraph }
        .map { destination -> routes.first { destination.hasRoute(it) } }

    private val routes = listOf(
        WalletsRoute::class, WalletDetailRoute::class, ConnectRoute::class, ChangePasswordRoute::class, ScanRoute::class,
        ActivityRoute::class, SettingsRoute::class, LanguageRoute::class, SubmitWalletRoute::class,
    )

    @Test
    fun eachTabKeepsItsOwnStack() {
        show()
        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        tap(Tab.Settings)
        assertEquals(Tab.Settings, router.tab.value)
        compose.onNodeWithTag("settings-root").assertExists()
        onUi { nav.navigate(LanguageRoute) }
        tap(Tab.Wallets)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class, WalletDetailRoute::class), stack())
        tap(Tab.Settings)
        compose.onNodeWithTag("language").assertExists()
    }

    @Test
    fun reselectingATabPopsToItsRoot() {
        show()
        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        tap(Tab.Wallets)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class), stack())
    }

    @Test
    fun openShowsWalletsWithTheRouteAsItsOnlyPage() {
        show()
        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        tap(Tab.Settings)
        onUi { router.open(ConnectRoute.walletLevel(wallet)) }
        assertEquals(Tab.Wallets, router.tab.value)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class, ConnectRoute::class), stack())
        compose.onNodeWithTag("connect").assertExists()
    }

    @Test
    fun resetPopsTheShownWalletsStackOrDropsTheSavedOne() {
        show()
        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        onUi { router.resetNavigation() }
        assertEquals(listOf<KClass<*>>(WalletsRoute::class), stack())

        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        tap(Tab.Settings)
        onUi { nav.navigate(LanguageRoute) }
        onUi { router.resetNavigation() }
        // Other tabs are untouched…
        compose.onNodeWithTag("language").assertExists()
        // …and Wallets opens on its root next time.
        tap(Tab.Wallets)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class), stack())
        // Saving and restoring the stacks still works afterwards.
        tap(Tab.Settings)
        compose.onNodeWithTag("language").assertExists()
        tap(Tab.Wallets)
        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        tap(Tab.Activity)
        tap(Tab.Wallets)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class, WalletDetailRoute::class), stack())
    }

    @Test
    fun aResetThenAnOpenRunInOrder() {
        show()
        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        tap(Tab.Activity)
        onUi {
            router.resetNavigation()
            router.open(ConnectRoute.walletLevel(wallet))
        }
        assertEquals(listOf<KClass<*>>(WalletsRoute::class, ConnectRoute::class), stack())
    }

    @Test
    fun aSelectedTabIsShownAndBackFromATabRootReturnsToWallets() {
        show()
        onUi { router.selectTab(Tab.Scan) }
        compose.onNodeWithTag("scan-root").assertExists()
        onUi { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("wallets-root").assertExists()
        assertEquals(Tab.Wallets, router.tab.value)
    }

    /**
     * Review fix: Back on another tab's root used to let the NavHost pop that tab's graph, which showed Wallets' root
     * while Wallets' saved stack stayed behind and reappeared on a later tab switch.
     */
    @Test
    fun backFromATabRootBringsWalletsBackWithItsStack() {
        show()
        onUi { nav.navigate(WalletDetailRoute(wallet)) }
        tap(Tab.Scan)
        systemBack()
        assertEquals(Tab.Wallets, router.tab.value)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class, WalletDetailRoute::class), stack())
        compose.onNodeWithTag("wallet-detail").assertExists()

        // Leaving the page by Back is final: a later switch does not bring it back.
        systemBack()
        assertEquals(listOf<KClass<*>>(WalletsRoute::class), stack())
        tap(Tab.Scan)
        tap(Tab.Wallets)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class), stack())

        // From a tab that was opened on Wallets' root, Back lands on that root.
        tap(Tab.Settings)
        systemBack()
        compose.onNodeWithTag("wallets-root").assertExists()
        assertEquals(Tab.Wallets, router.tab.value)
    }

    /**
     * Review fix: a root-level page ("Submit a Wallet") keeps the tab it was opened from selected, and a tap on that tab
     * used to restore the very same stack (the page stayed) instead of popping to the tab's root.
     */
    @Test
    fun reselectingTheTabOfARootLevelPagePopsToItsRoot() {
        show()
        tap(Tab.Settings)
        onUi { nav.navigate(LanguageRoute) }
        onUi { nav.navigate(SubmitWalletRoute) }
        tap(Tab.Settings)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class, SettingsRoute::class), stack())
        compose.onNodeWithTag("settings-root").assertExists()

        // From Wallets (Connect › Submit a Wallet) the same holds for the Wallets tab.
        tap(Tab.Wallets)
        onUi {
            nav.navigate(ConnectRoute.walletLevel(wallet))
            nav.navigate(SubmitWalletRoute)
        }
        compose.onNodeWithTag(Tab.Wallets.testTag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        tap(Tab.Wallets)
        assertEquals(listOf<KClass<*>>(WalletsRoute::class), stack())
    }

    private fun systemBack() = onUi { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /**
     * Review fix: the NavHost sat in a different layout for the bar and the rail, so crossing 600 dp (rotating a phone,
     * resizing a window; the activity is not recreated) rebuilt every page and dropped its remembered state.
     */
    @Test
    @Config(qualifiers = "w900dp-h800dp")
    fun crossingTheRailWidthKeepsThePagesState() {
        var width by mutableStateOf(400.dp)
        var created = 0
        compose.setContent {
            nav = rememberNavController()
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Box(Modifier.width(width)) {
                    MainScaffold(router = router, showsScanBadge = false, navController = nav) {
                        navigation<WalletsGraph>(startDestination = WalletsRoute) {
                            composable<WalletsRoute> {
                                remember { created++ }
                                Text("wallets-root", Modifier.testTag("wallets-root"))
                            }
                        }
                        navigation<ScanGraph>(startDestination = ScanRoute) { page<ScanRoute>("scan-root") }
                        navigation<ActivityGraph>(startDestination = ActivityRoute) { page<ActivityRoute>("activity-root") }
                        navigation<SettingsGraph>(startDestination = SettingsRoute) { page<SettingsRoute>("settings-root") }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, created)
        width = 700.dp
        compose.waitForIdle()
        // The rail: the tabs are stacked.
        val wallets = compose.onNodeWithTag(Tab.Wallets.testTag).getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithTag(Tab.Settings.testTag).getUnclippedBoundsInRoot()
        assertTrue(settings.top > wallets.top, "no navigation rail at 700 dp")
        compose.onNodeWithTag("wallets-root").assertExists()
        width = 400.dp
        compose.waitForIdle()
        assertEquals(1, created, "the page was rebuilt")
    }

    @Test
    fun submitAWalletKeepsTheCurrentTabSelected() {
        show()
        tap(Tab.Settings)
        onUi { nav.navigate(SubmitWalletRoute) }
        compose.onNodeWithTag("submit-wallet").assertExists()
        assertEquals(Tab.Settings, router.tab.value)
        compose.onNodeWithTag(Tab.Settings.testTag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun modalRoutesHideTheTabBar() {
        show()
        compose.onNodeWithTag(Tab.Scan.testTag).assertExists()
        onUi { nav.navigate(ChangePasswordRoute(wallet.toString())) }
        compose.onNodeWithTag(Tab.Scan.testTag).assertDoesNotExist()
        onUi { nav.popBackStack() }
        compose.onNodeWithTag(Tab.Scan.testTag).assertExists()
    }

    @Test
    fun theScanBadgeSaysARequestIsWaiting() {
        show(badge = true)
        compose.onNodeWithText("1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(Tab.Scan.testTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "A signing request is waiting"))
    }
}
