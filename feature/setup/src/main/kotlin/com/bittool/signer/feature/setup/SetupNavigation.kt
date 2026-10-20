package com.bittool.signer.feature.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.navigation.RecoveryPhraseRoute
import com.bittool.signer.core.navigation.SetupPresentation
import com.bittool.signer.core.navigation.WalletRoute

/**
 * Contract stub (ARCHITECTURE.md §3, W2-setup replaces the body): the saved wallet's "Recovery Phrase" page
 * (setup-backup.md §10, wallets-pairing.md §8.2), registered by `:app` inside the Wallets graph. Modal (no bottom
 * bar). Calls `SignerStore.beginSensitiveSession()` on entry and `endSensitiveSession()` + wipe on exit.
 */
fun NavGraphBuilder.recoveryPhraseDestination(navController: NavController) {
    composable<RecoveryPhraseRoute> { }
}

/**
 * Contract stub (W2-setup implements): the whole create/import flow of one [presentation] (iOS `WalletSetupFlow`,
 * setup-backup.md §1–§10) as a full-screen overlay that `:app` draws above the stages after the onboarding pager
 * (`presentation.showsIntroduction`). Owns its own state holder keyed by `presentation.id`; nothing of it is saved.
 * The holder lives while this composable is in the composition (it survives activity recreation, e.g. through a
 * `ViewModel` in a `ViewModelStoreOwner` remembered per `presentation.id` and retained by the activity), and when the
 * presentation ends it runs `clearSecrets()` + `SignerStore.endSensitiveSession()` (setup-backup.md §1.5, §15.2).
 * Windows it opens (discard dialog, language picker, recovery scanner `Dialog`, tips and cards sheets) honour
 * `LocalWindowGateOpen` like every `SignerAlertDialog` / `SignerBottomSheet`.
 * Calls [onFinished] with the route to open after the overlay disappeared (`ConnectRoute.walletLevel(id)` for
 * "Pair a Wallet", null for "Done") and [onCancelled] for Cancel / Discard / Back on the first step. `:app` maps them
 * to `AppRouter.finishSetup` / `cancelSetup`.
 */
@Composable
fun SetupFlowHost(
    presentation: SetupPresentation,
    onFinished: (WalletRoute?) -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
}
