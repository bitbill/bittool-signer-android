package com.bittool.signer.feature.wallets

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bittool.signer.core.navigation.ChangePasswordRoute
import com.bittool.signer.core.navigation.DeleteWalletRoute
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.navigation.WalletsRoute
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** Stands in for a `hiltViewModel()` of the wallet page: its handle comes from `createSavedStateHandle()`, as Hilt's does. */
    class EntryViewModel(val handle: SavedStateHandle) : ViewModel()

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

    /**
     * iOS `ChangePasswordSheet(onChanged:)` → "Password Changed" on the wallet page (wallets-pairing.md §5, inventory
     * row 31). The result travels in the wallet page's back-stack entry; the page's ViewModel has its own
     * `SavedStateHandle` and never sees it, which is why the destination glue reads the entry (the first port put the
     * flag on the entry but read it from the ViewModel, so the toast never appeared).
     */
    @Test
    fun aPasswordChangeReachesTheWalletPageUnderIt() {
        var pending = false
        var detailEntry: NavBackStackEntry? = null
        var detailViewModel: EntryViewModel? = null
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = WalletsRoute) {
                composable<WalletsRoute> {}
                composable<WalletDetailRoute> { entry ->
                    detailEntry = entry
                    detailViewModel = viewModel { EntryViewModel(createSavedStateHandle()) }
                    val due by PasswordChangedResult.pending(entry).collectAsState()
                    pending = due
                }
                composable<ChangePasswordRoute> {}
            }
        }
        val id = WalletFixtures.readyId.toString()
        compose.runOnIdle { nav.navigate(WalletDetailRoute(id)) }
        compose.runOnIdle { nav.navigate(ChangePasswordRoute(id)) }
        compose.runOnIdle {
            assertFalse(pending)
            val changePassword = top()
            PasswordChangedResult.report(nav, from = changePassword)
            nav.close(changePassword)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(top().destination.hasRoute(WalletDetailRoute::class))
            assertTrue(pending, "the wallet page learns that the password changed")
            assertNull(
                detailViewModel!!.handle.get<Boolean>(PasswordChangedResult.KEY),
                "a ViewModel's SavedStateHandle is not the entry's: the result must be read from the entry",
            )
            PasswordChangedResult.consume(detailEntry!!)
        }
        compose.runOnIdle { assertFalse(pending, "shown once") }
    }

    @Test
    fun onlyTheTopChangePasswordPageReportsAResult() {
        var pending = false
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = WalletsRoute) {
                composable<WalletsRoute> {}
                composable<WalletDetailRoute> { entry ->
                    val due by PasswordChangedResult.pending(entry).collectAsState()
                    pending = due
                }
                composable<ChangePasswordRoute> {}
            }
        }
        val id = WalletFixtures.readyId.toString()
        compose.runOnIdle { nav.navigate(WalletDetailRoute(id)) }
        compose.runOnIdle { nav.navigate(ChangePasswordRoute(id)) }
        compose.runOnIdle {
            val changePassword = top()
            // `:app` reset the Wallets stack first: the late success has no wallet page to report to.
            nav.popBackStack<WalletsRoute>(inclusive = false)
            PasswordChangedResult.report(nav, from = changePassword)
        }
        compose.runOnIdle { assertFalse(pending) }
    }
}
