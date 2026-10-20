package com.bittool.signer.feature.wallets

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bittool.signer.core.navigation.DeleteWalletRoute
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.navigation.WalletsRoute
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A page that closes itself never pops a second page: the page's own close, the wallet-gone close and `:app`'s
 * Wallets-stack reset may all happen for one deletion (wallets-pairing.md §8.4, §13.2).
 */
@RunWith(RobolectricTestRunner::class)
class WalletsNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun host() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = WalletsRoute) {
                composable<WalletsRoute> {}
                composable<WalletDetailRoute> {}
                composable<DeleteWalletRoute> {}
            }
        }
        compose.waitForIdle()
    }

    private fun top(): NavBackStackEntry = nav.currentBackStackEntry!!

    @Test
    fun closingAPageUnderAModalPopsBothAndAnyRepeatIsANoOp() {
        host()
        val id = WalletFixtures.readyId.toString()
        compose.runOnIdle {
            nav.navigate(WalletDetailRoute(id))
            nav.navigate(DeleteWalletRoute(id))
        }
        compose.waitForIdle()
        lateinit var detail: NavBackStackEntry
        lateinit var delete: NavBackStackEntry
        compose.runOnIdle {
            delete = top()
            detail = nav.previousBackStackEntry!!
            assertTrue(detail.destination.hasRoute(WalletDetailRoute::class))
            // The wallet is gone: the detail page closes first (taking Delete with it), then Delete's own success arrives.
            nav.close(detail)
            nav.close(delete)
            nav.close(detail)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(top().destination.hasRoute(WalletsRoute::class), "the tab root stays") }
    }

    @Test
    fun closingTheTopPageAfterAResetDoesNothing() {
        host()
        val id = WalletFixtures.readyId.toString()
        compose.runOnIdle { nav.navigate(WalletDetailRoute(id)) }
        compose.waitForIdle()
        compose.runOnIdle {
            val detail = top()
            // `:app` reset the Wallets stack first (navigation reset epoch after a deletion).
            nav.popBackStack<WalletsRoute>(inclusive = false)
            nav.close(detail)
            assertTrue(top().destination.hasRoute(WalletsRoute::class))
            assertEquals(null, nav.previousBackStackEntry, "nothing under the tab root was popped")
        }
    }
}
