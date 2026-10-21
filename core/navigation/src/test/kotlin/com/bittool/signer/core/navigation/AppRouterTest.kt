package com.bittool.signer.core.navigation

import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.WalletId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Test

/** Port of `BitToolSignerTests/ScanRequestRouterTests.swift` plus the rest of iOS `AppRouter` (app-shell.md §9.3). */
class AppRouterTest {
    private val wallet = WalletId.parse("10000000-0000-0000-0000-000000000001")!!

    @Test
    fun askingOpensTheCameraOnceAndOnlyOnce() {
        val router = AppRouter()
        assertFalse(router.consumeScanRequest(), "nothing has been asked for yet")
        router.startScanning()
        assertEquals(Tab.Wallets, router.tab.value, "the camera opens over the current tab")
        assertTrue(router.consumeScanRequest(), "the camera opens")
        assertFalse(router.consumeScanRequest(), "it does not open a second time")
    }

    @Test
    fun theCameraDoesNotReopenAfterASignatureIsFinished() {
        val router = AppRouter()
        router.startScanning()
        assertTrue(router.consumeScanRequest())
        for (step in listOf("request arrives", "review", "signature result", "Done")) {
            assertFalse(router.consumeScanRequest(), "reopened the camera after: $step")
        }
    }

    @Test
    fun askingAgainOpensTheCameraAgain() {
        val router = AppRouter()
        router.startScanning()
        assertTrue(router.consumeScanRequest())
        assertFalse(router.consumeScanRequest())
        router.startScanning()
        assertTrue(router.consumeScanRequest(), "a second tap on the toolbar opens it again")
        assertFalse(router.consumeScanRequest())
    }

    @Test
    fun movingToTheScanTabByHandNeverOpensTheCamera() {
        val router = AppRouter()
        router.selectTab(Tab.Scan)
        assertFalse(router.consumeScanRequest(), "only the toolbar button opens the camera")
        router.onTabClicked(Tab.Scan)
        assertFalse(router.consumeScanRequest())
    }

    @Test
    fun theIntroductionShowsOnlyForTheFirstWalletUntilCompleted() {
        val router = AppRouter()
        router.startSetup(SetupIntent.Restore, firstWallet = true, introductionCompleted = false)
        val first = router.setup.value!!
        assertTrue(first.showsIntroduction)
        assertEquals(SetupIntent.Restore, first.intent)
        router.startSetup(SetupIntent.Create, firstWallet = true, introductionCompleted = true)
        val second = router.setup.value!!
        assertFalse(second.showsIntroduction)
        assertNotEquals(first.id, second.id, "every presentation is new")
        router.startSetup(SetupIntent.Create, firstWallet = false, introductionCompleted = false)
        assertFalse(router.setup.value!!.showsIntroduction)
    }

    @Test
    fun finishingSetupOpensTheRouteOnlyAfterTheOverlayDismissed() = runTest {
        val router = AppRouter()
        router.startSetup(SetupIntent.Create, firstWallet = true, introductionCompleted = true)
        router.selectTab(Tab.Settings)
        router.finishSetup(ConnectRoute.walletLevel(wallet))
        assertNull(router.setup.value)
        assertEquals(Tab.Settings, router.tab.value, "nothing opens while the overlay animates away")
        router.setupDidDismiss()
        assertEquals(Tab.Wallets, router.tab.value)
        assertEquals(WalletStackRequest.Replace(ConnectRoute(wallet.toString(), "wallet", null)), router.walletStackRequests.first())
        router.setupDidDismiss()
        router.resetNavigation()
        assertEquals(WalletStackRequest.Reset, router.walletStackRequests.first(), "a second dismissal opens nothing")
    }

    @Test
    fun aCancelledOrSwipedSetupOpensNothing() = runTest {
        val router = AppRouter()
        router.startSetup(SetupIntent.Create, firstWallet = false, introductionCompleted = true)
        router.cancelSetup()
        router.setupDidDismiss()
        router.resetNavigation()
        assertEquals(listOf<WalletStackRequest>(WalletStackRequest.Reset), router.walletStackRequests.take(1).toList())
        assertEquals(Tab.Wallets, router.tab.value)
    }

    @Test
    fun openReplacesTheWalletStackOnTheWalletsTab() = runTest {
        val router = AppRouter()
        router.selectTab(Tab.Activity)
        router.open(WalletDetailRoute(wallet))
        router.open(AddressesRoute(wallet))
        assertEquals(Tab.Wallets, router.tab.value)
        assertEquals(
            listOf(WalletStackRequest.Replace(WalletDetailRoute(wallet.toString())), WalletStackRequest.Replace(AddressesRoute(wallet.toString(), "bitcoin"))),
            router.walletStackRequests.take(2).toList(),
        )
    }

    @Test
    fun reselectingTheCurrentTabEmitsAReselection() = runTest {
        val router = AppRouter()
        val reselected = async(start = CoroutineStart.UNDISPATCHED) { router.reselections.first() }
        router.onTabClicked(Tab.Scan)
        assertEquals(Tab.Scan, router.tab.value, "a different tab is selected, not reselected")
        router.onTabClicked(Tab.Scan)
        assertEquals(Tab.Scan, reselected.await())
    }

    @Test
    fun routesRoundTripThroughSerialization() {
        val routes: List<SignerRoute> = listOf(
            WalletsGraph, ScanGraph, ActivityGraph, SettingsGraph, WalletsRoute, WalletDetailRoute(wallet),
            ConnectRoute.walletLevel(wallet), ConnectRoute.coinLevel(wallet, Chain.Tron), AddressesRoute(wallet),
            ChangePasswordRoute(wallet.toString()), DeleteWalletRoute(wallet.toString()), RepairWalletRoute(wallet.toString()),
            RecoveryPhraseRoute(wallet.toString()), ScanRoute, ScannerRoute, ActivityRoute, ActivityDetailRoute("r/1?&#%+ 中"),
            SignedRequestRoute("r"), SettingsRoute, LanguageRoute, NetworksRoute, NetworkReferenceRoute("evm:*"), CompatibilityRoute,
            ContactRoute, OpenSourceRoute, ThirdPartyNoticesRoute, LegalDocumentRoute(LegalDocument.Privacy), SubmitWalletRoute,
        )
        for (route in routes) {
            @Suppress("UNCHECKED_CAST")
            val serializer = serializer(route::class.java) as kotlinx.serialization.KSerializer<SignerRoute>
            val text = Json.encodeToString(serializer, route)
            assertEquals(route, Json.decodeFromString(serializer, text), text)
        }
        assertEquals(PairingLevel.Coin, ConnectRoute.coinLevel(wallet, Chain.Tron).pairingLevel)
        assertEquals(Chain.Tron, ConnectRoute.coinLevel(wallet, Chain.Tron).initialChain)
        assertEquals(PairingLevel.Wallet, ConnectRoute.walletLevel(wallet).pairingLevel)
        assertEquals(LegalDocument.Privacy, LegalDocumentRoute(LegalDocument.Privacy).legalDocument)
        assertTrue(ScannerRoute::class in ModalRouteClasses)
        assertFalse(WalletDetailRoute::class in ModalRouteClasses)
        assertEquals(listOf(Tab.Wallets, Tab.Scan, Tab.Activity, Tab.Settings), Tab.entries)
    }
}
