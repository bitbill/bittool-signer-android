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
import com.bittool.signer.feature.settings.about.ContactScreen
import com.bittool.signer.feature.settings.about.LegalDocumentScreen
import com.bittool.signer.feature.settings.about.OpenSourceScreen
import com.bittool.signer.feature.settings.about.SubmitWalletScreen
import com.bittool.signer.feature.settings.about.ThirdPartyNoticesScreen
import com.bittool.signer.feature.settings.compatibility.CompatibilityScreen
import com.bittool.signer.feature.settings.language.LanguageScreen
import com.bittool.signer.feature.settings.reference.NetworkReferenceScreen
import com.bittool.signer.feature.settings.reference.NetworksScreen

/**
 * The Settings tab inside `navigation<SettingsGraph>(startDestination = SettingsRoute)` (ARCHITECTURE.md §2.2): the
 * root and every page under it. Transitions are the NavHost's (`:app`); Back pops one page.
 */
fun NavGraphBuilder.settingsDestinations(navController: NavController) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<SettingsRoute> { SettingsScreen(onNavigate = { route -> navController.navigate(route) }) }
    composable<LanguageRoute> { LanguageScreen(onBack = back) }
    composable<NetworksRoute> {
        NetworksScreen(onBack = back, onOpenNetwork = { chain -> navController.navigate(NetworkReferenceRoute(chain)) })
    }
    composable<NetworkReferenceRoute> { NetworkReferenceScreen(onBack = back) }
    composable<CompatibilityRoute> {
        CompatibilityScreen(onBack = back, onSubmitWallet = { navController.navigate(SubmitWalletRoute) })
    }
    composable<ContactRoute> { ContactScreen(onBack = back) }
    composable<OpenSourceRoute> {
        OpenSourceScreen(onBack = back, onOpenNotices = { navController.navigate(ThirdPartyNoticesRoute) })
    }
    composable<ThirdPartyNoticesRoute> { ThirdPartyNoticesScreen(onBack = back) }
    composable<LegalDocumentRoute> { LegalDocumentScreen(onBack = back) }
}

/**
 * Root-level destinations owned by `:feature:settings` but reachable from other tabs (registered by `:app` outside the
 * tab graphs): "Submit a Wallet", opened from Connect (Wallets) and from Compatibility (Settings). It keeps the current
 * tab selected; Back returns to the page that opened it.
 */
fun NavGraphBuilder.settingsSharedDestinations(navController: NavController) {
    composable<SubmitWalletRoute> { SubmitWalletScreen(onBack = { navController.popBackStack() }) }
}
