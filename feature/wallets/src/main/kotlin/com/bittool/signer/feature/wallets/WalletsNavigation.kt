package com.bittool.signer.feature.wallets

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.navigation.AddressesRoute
import com.bittool.signer.core.navigation.ChangePasswordRoute
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.core.navigation.DeleteWalletRoute
import com.bittool.signer.core.navigation.RepairWalletRoute
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.navigation.WalletsRoute

/**
 * Contract stub (ARCHITECTURE.md §3, W2-wallets replaces the bodies): registers every `:feature:wallets` destination
 * inside `:app`'s `navigation<WalletsGraph>(startDestination = WalletsRoute)` — the Wallets tab root, wallet detail,
 * Connect, Addresses and the Change Password / Delete / Repair pages. The Add Wallet menu, the rename and "Unable to
 * Rename" dialogs, the delete password dialog, the address detail sheet and the address context menu are hosted by
 * these screens, not destinations (ARCHITECTURE.md §2.5 rows 26–43). Cross-feature navigation goes through `AppRouter` and
 * `navController.navigate(<core:navigation route>)` only.
 */
fun NavGraphBuilder.walletsDestinations(navController: NavController) {
    composable<WalletsRoute> { }
    composable<WalletDetailRoute> { }
    composable<ConnectRoute> { }
    composable<AddressesRoute> { }
    composable<ChangePasswordRoute> { }
    composable<DeleteWalletRoute> { }
    composable<RepairWalletRoute> { }
}
