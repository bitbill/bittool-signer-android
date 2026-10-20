package com.bittool.signer.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.navigation.CompatibilityRoute
import com.bittool.signer.core.navigation.ContactRoute
import com.bittool.signer.core.navigation.LanguageRoute
import com.bittool.signer.core.navigation.LegalDocumentRoute
import com.bittool.signer.core.navigation.NetworkReferenceRoute
import com.bittool.signer.core.navigation.NetworksRoute
import com.bittool.signer.core.navigation.OpenSourceRoute
import com.bittool.signer.core.navigation.SettingsRoute
import com.bittool.signer.core.navigation.SubmitWalletRoute
import com.bittool.signer.core.navigation.ThirdPartyNoticesRoute

/**
 * Contract stub (ARCHITECTURE.md §3, W2-activity-settings replaces the bodies): the Settings tab inside
 * `navigation<SettingsGraph>(startDestination = SettingsRoute)`.
 */
fun NavGraphBuilder.settingsDestinations(navController: NavController) {
    composable<SettingsRoute> { }
    composable<LanguageRoute> { }
    composable<NetworksRoute> { }
    composable<NetworkReferenceRoute> { }
    composable<CompatibilityRoute> { }
    composable<ContactRoute> { }
    composable<OpenSourceRoute> { }
    composable<ThirdPartyNoticesRoute> { }
    composable<LegalDocumentRoute> { }
}

/**
 * Root-level destinations owned by `:feature:settings` but reachable from other tabs (registered by `:app` outside the
 * tab graphs): "Submit a Wallet", opened from Connect (Wallets) and from Compatibility (Settings).
 */
fun NavGraphBuilder.settingsSharedDestinations(navController: NavController) {
    composable<SubmitWalletRoute> { }
}
