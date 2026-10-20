package com.bittool.signer.feature.setup

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.navigation.RecoveryPhraseRoute
import com.bittool.signer.core.navigation.SetupPresentation
import com.bittool.signer.core.navigation.WalletRoute
import com.bittool.signer.feature.setup.flow.SetupFlow
import com.bittool.signer.feature.setup.flow.WalletSetupViewModel
import com.bittool.signer.feature.setup.reveal.RecoveryPhraseScreen

/**
 * The saved wallet's "Recovery Phrase" page (setup-backup §10, wallets-pairing §8.2), registered by `:app` inside the
 * Wallets graph. Modal (no bottom bar): it slides up and back down like the iOS sheet. Its ViewModel begins a sensitive
 * session on entry and ends it (wiping the decrypted phrase) when the entry is destroyed. Cancel / Done / Back pop it
 * (Back is consumed while unlocking); a wallet that no longer exists pops it too.
 */
fun NavGraphBuilder.recoveryPhraseDestination(navController: NavController) {
    composable<RecoveryPhraseRoute>(
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, SignerMotion.smooth(0.35)) + fadeIn(SignerMotion.quick()) },
        exitTransition = { fadeOut(SignerMotion.quick()) },
        popEnterTransition = { fadeIn(SignerMotion.quick()) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, SignerMotion.smooth(0.35)) + fadeOut(SignerMotion.quick()) },
    ) { entry ->
        RecoveryPhraseScreen(
            onClose = {
                // Only the visible page pops itself (a Close racing a system Back never pops a second page).
                if (navController.currentBackStackEntry?.id == entry.id && entry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    navController.popBackStack()
                }
            },
        )
    }
}

/**
 * The whole create/import flow of one [presentation] (iOS `WalletSetupFlow`, setup-backup §1–§9) as the full-screen
 * overlay `:app` draws above the stages after the onboarding pager. Its `WalletSetupViewModel` lives in a
 * `ViewModelStore` of its own, keyed by `presentation.id` and retained by the hosting owner (the activity), so it
 * survives configuration changes but never process death (nothing of it is saved). When the presentation ends — the
 * overlay leaves the composition other than for a configuration change — that store is cleared: the ViewModel clears
 * its secrets and ends the sensitive session (setup-backup §1.5, §15.2). Its windows (discard dialog, language choice,
 * recovery scanner, tips and cards sheets) honour `LocalWindowGateOpen`.
 *
 * [onFinished] gets the route to open once the overlay has disappeared (`ConnectRoute.walletLevel(id)` for "Pair a
 * Wallet", null for "Done"); [onCancelled] is Cancel / Discard / Back on the first step. `:app` maps them to
 * `AppRouter.finishSetup` / `cancelSetup`.
 */
@Composable
fun SetupFlowHost(
    presentation: SetupPresentation,
    onFinished: (WalletRoute?) -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "SetupFlowHost needs a ViewModelStoreOwner" }
    val stores = viewModel(parent, key = SetupViewModelStores.KEY) { SetupViewModelStores() }
    val owner = remember(stores, presentation.id, parent) { PresentationViewModelStoreOwner(stores.storeFor(presentation.id), parent) }
    val activity = LocalActivity.current
    DisposableEffect(stores, presentation.id) {
        onDispose {
            // A rotation or a locale change recreates the activity: the draft stays. Anything else ends it.
            if (activity?.isChangingConfigurations != true) stores.clear(presentation.id)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val model = hiltViewModel<WalletSetupViewModel, WalletSetupViewModel.Factory>(key = "setup-${presentation.id}") { factory ->
            factory.create(presentation.intent)
        }
        SetupFlow(model, onFinished = onFinished, onCancelled = onCancelled, modifier = modifier)
    }
}

/**
 * One `ViewModelStore` per setup presentation, held by the hosting owner (the activity's store): it outlives activity
 * recreation and is cleared when its presentation ends or the activity finishes.
 */
internal class SetupViewModelStores : ViewModel() {
    private val stores = HashMap<Long, ViewModelStore>()

    fun storeFor(id: Long): ViewModelStore = stores.getOrPut(id) { ViewModelStore() }

    fun clear(id: Long) {
        stores.remove(id)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }

    companion object {
        const val KEY = "com.bittool.signer.feature.setup.SetupViewModelStores"
    }
}

/**
 * The owner of one presentation's store. ViewModels are created by the parent's (Hilt) factory with the parent's
 * creation extras, except that their `SavedStateHandle` bookkeeping lives in this store (so it is dropped with it and
 * never written into the activity's saved state).
 */
private class PresentationViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: ViewModelStoreOwner,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras((parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty)
            .apply { set(VIEW_MODEL_STORE_OWNER_KEY, this@PresentationViewModelStoreOwner) }
}
