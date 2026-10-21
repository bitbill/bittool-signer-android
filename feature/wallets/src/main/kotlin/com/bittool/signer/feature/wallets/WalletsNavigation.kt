package com.bittool.signer.feature.wallets

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.AddressesRoute
import com.bittool.signer.core.navigation.ChangePasswordRoute
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.core.navigation.DeleteWalletRoute
import com.bittool.signer.core.navigation.ModalRouteClasses
import com.bittool.signer.core.navigation.RecoveryPhraseRoute
import com.bittool.signer.core.navigation.RepairWalletRoute
import com.bittool.signer.core.navigation.SubmitWalletRoute
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.navigation.WalletsRoute
import com.bittool.signer.feature.wallets.addresses.AddressesScreen
import com.bittool.signer.feature.wallets.connect.ConnectScreen
import com.bittool.signer.feature.wallets.detail.WalletDetailActions
import com.bittool.signer.feature.wallets.detail.WalletDetailScreen
import com.bittool.signer.feature.wallets.list.WalletsScreen
import com.bittool.signer.feature.wallets.security.ChangePasswordScreen
import com.bittool.signer.feature.wallets.security.DeleteWalletScreen
import com.bittool.signer.feature.wallets.security.RepairWalletScreen
import kotlinx.coroutines.flow.StateFlow

/**
 * Registers every `:feature:wallets` destination inside `:app`'s `navigation<WalletsGraph>(startDestination =
 * WalletsRoute)` (ARCHITECTURE §2.2, inventory rows 26–43): the Wallets tab root, wallet detail, Connect, Addresses and
 * the Change Password / Delete / Repair pages. The Add Wallet menu, the rename and "Unable to Rename" dialogs, the delete
 * password dialog, the address detail sheet and the address context menu are hosted by these screens.
 *
 * Pushed pages use the NavHost's transitions (shared-axis, predictive back); the three modal pages slide up and down
 * (ARCHITECTURE §4.6) while the page under them stays. Every page about a wallet closes itself when that wallet is no
 * longer in the list (deleted, or a restored back stack that outlived it). Cross-feature pages are reached through
 * their `:core:navigation` routes: the saved wallet's Recovery Phrase (`:feature:setup`) and Submit a Wallet
 * (`:feature:settings`). Navigation clicks are dropped unless the page is resumed (no double pushes during a transition).
 */
fun NavGraphBuilder.walletsDestinations(navController: NavController) {
    composable<WalletsRoute>(
        // The card of a wallet whose device key is gone opens the modal removal page right over the list (QA L4).
        exitTransition = { keepUnderModal() },
        popEnterTransition = { stayUnderModal() },
    ) {
        WalletsScreen(
            onOpenWallet = dropUnlessResumedWith { id: WalletId -> navController.navigate(WalletDetailRoute(id)) },
            onPair = dropUnlessResumedWith { id: WalletId -> navController.navigate(ConnectRoute.walletLevel(id)) },
            onAddresses = dropUnlessResumedWith { id: WalletId -> navController.navigate(AddressesRoute(id)) },
            onRemove = dropUnlessResumedWith { id: WalletId -> navController.navigate(DeleteWalletRoute(id.toString())) },
        )
    }

    composable<WalletDetailRoute>(
        exitTransition = { keepUnderModal() },
        popEnterTransition = { stayUnderModal() },
    ) { entry ->
        val walletId = entry.toRoute<WalletDetailRoute>().walletId
        val passwordChanged by PasswordChangedResult.pending(entry).collectAsStateWithLifecycle()
        WalletDetailScreen(
            actions = WalletDetailActions(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onConnect = dropUnlessResumedWith { chain -> navController.navigate(ConnectRoute(walletId, ConnectRoute.LEVEL_COIN, chain.wire)) },
                onConnectWallet = dropUnlessResumed { navController.navigate(ConnectRoute(walletId)) },
                onAddresses = dropUnlessResumed { navController.navigate(AddressesRoute(walletId)) },
                onRecoveryPhrase = dropUnlessResumed { navController.navigate(RecoveryPhraseRoute(walletId)) },
                onChangePassword = dropUnlessResumed { navController.navigate(ChangePasswordRoute(walletId)) },
                onDelete = dropUnlessResumed { navController.navigate(DeleteWalletRoute(walletId)) },
                onRepair = dropUnlessResumed { navController.navigate(RepairWalletRoute(walletId)) },
            ),
            onClose = { navController.close(entry) },
            passwordChanged = passwordChanged,
            onPasswordChangedShown = { PasswordChangedResult.consume(entry) },
        )
    }

    composable<ConnectRoute> { entry ->
        ConnectScreen(
            onBack = dropUnlessResumed { navController.popBackStack() },
            onSubmitWallet = dropUnlessResumed { navController.navigate(SubmitWalletRoute) },
            onClose = { navController.close(entry) },
        )
    }

    composable<AddressesRoute> { entry ->
        AddressesScreen(
            onBack = dropUnlessResumed { navController.popBackStack() },
            onClose = { navController.close(entry) },
        )
    }

    composable<ChangePasswordRoute>(
        enterTransition = { modalEnter() },
        exitTransition = { keepUnderModal() },
        popEnterTransition = { stayUnderModal() },
        popExitTransition = { modalExit() },
    ) { entry ->
        ChangePasswordScreen(
            onChanged = {
                // iOS `ChangePasswordSheet(onChanged:)`: the wallet page shows "Password Changed" once this page is gone.
                PasswordChangedResult.report(navController, from = entry)
                navController.close(entry)
            },
            onCancel = { navController.close(entry) },
            onClose = { navController.close(entry) },
        )
    }

    composable<DeleteWalletRoute>(
        enterTransition = { modalEnter() },
        exitTransition = { keepUnderModal() },
        popEnterTransition = { stayUnderModal() },
        popExitTransition = { modalExit() },
    ) { entry ->
        DeleteWalletScreen(
            onDeleted = { navController.close(entry) },
            onCancel = { navController.close(entry) },
            onClose = { navController.close(entry) },
        )
    }

    composable<RepairWalletRoute>(
        enterTransition = { modalEnter() },
        exitTransition = { keepUnderModal() },
        popEnterTransition = { stayUnderModal() },
        popExitTransition = { modalExit() },
    ) { entry ->
        RepairWalletScreen(
            onRepaired = { navController.close(entry) },
            onCancel = { navController.close(entry) },
            onClose = { navController.close(entry) },
        )
    }
}

/**
 * The Change Password page's result for the wallet page under it (iOS `ChangePasswordSheet(onChanged:)` →
 * `passwordChanged = true` → the "Password Changed" toast): a non-secret Boolean in the wallet page's **back-stack
 * entry** `SavedStateHandle`, read by the destination glue and handed to the screen.
 *
 * Not the wallet page ViewModel's `SavedStateHandle`: Navigation gives every ViewModel of an entry its own handle (keyed
 * by the ViewModel), distinct from `NavBackStackEntry.savedStateHandle`, so a flag set on the entry never reaches it.
 */
internal object PasswordChangedResult {
    const val KEY = "wallets.passwordChanged"

    /** Marks the wallet page under [from] (the Change Password entry, still on top of the stack). */
    fun report(navController: NavController, from: NavBackStackEntry) {
        if (navController.currentBackStackEntry?.id != from.id) return
        navController.previousBackStackEntry
            ?.takeIf { it.destination.hasRoute(WalletDetailRoute::class) }
            ?.savedStateHandle
            ?.set(KEY, true)
    }

    /** Whether [entry] (a wallet page) has a "Password Changed" toast due. */
    fun pending(entry: NavBackStackEntry): StateFlow<Boolean> = entry.savedStateHandle.getStateFlow(KEY, false)

    /** The toast was shown. */
    fun consume(entry: NavBackStackEntry) {
        entry.savedStateHandle[KEY] = false
    }
}

/**
 * Removes [entry] (and anything above it) from the back stack, once: a no-op when it is already gone, so the page's own
 * close, the wallet-gone close and `:app`'s Wallets-stack reset never pop a second page.
 */
internal fun NavController.close(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState == Lifecycle.State.DESTROYED) return
    if (currentBackStackEntry?.id == entry.id) {
        popBackStack()
        return
    }
    // Still on the stack under another page (e.g. the wallet page under Delete Wallet): pop down through it.
    val onStack = runCatching { getBackStackEntry(entry.destination.id) }.getOrNull()
    if (onStack?.id == entry.id) popBackStack(entry.destination.id, inclusive = true)
}

/** [dropUnlessResumed] for a callback with one argument (a wallet id, a chain). */
@Composable
private fun <T> dropUnlessResumedWith(block: (T) -> Unit): (T) -> Unit {
    val owner = LocalLifecycleOwner.current
    return { value: T -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block(value) }
}

private fun isModal(entry: NavBackStackEntry): Boolean =
    ModalRouteClasses.any { entry.destination.hasRoute(it) }

/** A modal page slides up from the bottom (ARCHITECTURE §4.6: vertical slide for modal routes). */
private fun modalEnter(): EnterTransition =
    slideInVertically(tween(MODAL_IN_MILLIS, easing = LinearOutSlowInEasing)) { it }

/** …and back down when it closes. */
private fun modalExit(): ExitTransition =
    slideOutVertically(tween(MODAL_OUT_MILLIS, easing = FastOutLinearInEasing)) { it } +
        fadeOut(tween(MODAL_OUT_MILLIS, easing = FastOutLinearInEasing), targetAlpha = 0.6f)

/** The page under a modal stays where it is; other pushes use the NavHost's own transition (null). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.keepUnderModal(): ExitTransition? =
    if (isModal(targetState)) ExitTransition.KeepUntilTransitionsFinished else null

private fun AnimatedContentTransitionScope<NavBackStackEntry>.stayUnderModal(): EnterTransition? =
    if (isModal(initialState)) EnterTransition.None else null

private const val MODAL_IN_MILLIS = 350
private const val MODAL_OUT_MILLIS = 250
