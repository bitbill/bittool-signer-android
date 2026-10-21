package com.bittool.signer.feature.wallets

import androidx.lifecycle.SavedStateHandle
import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.data.testing.FakeSignerPreferences
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.feature.wallets.common.WalletArgs
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.detail.WalletDetailViewModel
import com.bittool.signer.feature.wallets.detail.formatAddedDate
import com.bittool.signer.feature.wallets.list.WalletsViewModel
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The Wallets tab and the wallet page (wallets-pairing.md §4, §5, §8.6; app-shell.md §9.4 call sites). */
class WalletsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun cardsFollowTheVaultOrderWithFingerprintReadinessAndDuplicates() = runTest(main.dispatcher) {
        val store = WalletFixtures.store(WalletFixtures.state(repair = true, duplicates = true))
        val vm = WalletsViewModel(store, FakeSignerPreferences(), AppRouter())
        val state = observe(vm.uiState)
        val cards = state.value.wallets
        assertEquals(listOf(WalletFixtures.readyId, WalletFixtures.repairId, WalletFixtures.twinId), cards.map { it.id })
        assertEquals("73C5DA0A", cards[0].fingerprint)
        assertTrue(cards[0].ready && cards[0].duplicate)
        assertNull(cards[1].fingerprint, "no public data: \"Setup required\"")
        assertFalse(cards[1].ready, "the card offers \"Repair wallet data\"")
        assertEquals(WalletFixtures.repairId.uppercase, cards[1].avatarIdentity, "avatar seed falls back to the upper-case UUID")
        assertEquals("99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96", cards[0].avatarIdentity)
    }

    @Test
    fun theScanButtonAsksForTheCameraOnceOverWallets() {
        val router = AppRouter()
        val vm = WalletsViewModel(WalletFixtures.store(), FakeSignerPreferences(), router)
        vm.scan()
        assertEquals(Tab.Wallets, router.tab.value)
        assertTrue(router.consumeScanRequest())
        assertFalse(router.consumeScanRequest())
    }

    @Test
    fun addWalletStartsSetupWithTheIntroductionOnlyBeforeTheFirstCompletion() {
        val router = AppRouter()
        val preferences = FakeSignerPreferences(PreferenceValues(onboardingVersionCompleted = 0))
        val vm = WalletsViewModel(WalletFixtures.store(), preferences, router)
        vm.addWallet(SetupIntent.Restore)
        val presentation = router.setup.value!!
        assertEquals(SetupIntent.Restore, presentation.intent)
        assertFalse(presentation.showsIntroduction, "not the first wallet: the introduction is never shown")
    }

    @Test
    fun aDetailPageClosesItselfWhenItsWalletIsDeleted() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val state = observe(WalletDetailViewModel(store, WalletFixtures.args()).uiState)
        assertFalse(state.value.gone)
        assertEquals("Public fixture 1", state.value.identity?.name)
        store.setState { it.copy(wallets = emptyList(), publicWallets = emptyMap()) }
        assertTrue(state.value.gone)
    }

    @Test
    fun aPageNeverClosesWhileTheListIsLoadingOrAfterAFailedLoad() {
        val loading = WalletFixtures.state().copy(wallets = emptyList(), isLoading = true)
        assertFalse(WalletIdentity.isGone(loading, WalletFixtures.readyId))
        val failed = WalletFixtures.state().copy(wallets = emptyList(), walletLoadSucceeded = false)
        assertFalse(WalletIdentity.isGone(failed, WalletFixtures.readyId))
        assertTrue(WalletIdentity.isGone(WalletFixtures.state(), WalletArgs.walletId(SavedStateHandle(mapOf("walletId" to "nope")))))
    }

    @Test
    fun renameChangesOnlyTheDisplayNameAndReportsAFailure() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val vm = WalletDetailViewModel(store, WalletFixtures.args())
        val state = observe(vm.uiState)
        vm.startRename()
        assertTrue(state.value.renaming)
        vm.rename("Travel Signer")
        assertFalse(state.value.renaming, "the dialog closes on Save")
        advanceUntilIdle()
        assertEquals(listOf(Call.RenameWallet(WalletFixtures.readyId, "Travel Signer")), store.calls<Call.RenameWallet>())
        assertEquals("Travel Signer", state.value.identity?.name)

        store.onRenameWallet = { _, _ -> throw AppIssue("Choose a wallet name of 1–40 characters.") }
        vm.startRename()
        vm.rename("x".repeat(41))
        advanceUntilIdle()
        assertEquals(UiText.Ios("Choose a wallet name of 1–40 characters."), state.value.renameError)
        vm.dismissRenameError()
        assertNull(state.value.renameError)
    }

    @Test
    fun theFingerprintToCopyIsUpperCaseAndMissingWithoutPublicData() {
        val store = WalletFixtures.store(WalletFixtures.state(repair = true))
        assertEquals("73C5DA0A", WalletDetailViewModel(store, WalletFixtures.args()).fingerprintToCopy())
        assertNull(WalletDetailViewModel(store, WalletFixtures.args(WalletFixtures.repairId)).fingerprintToCopy())
    }

    @Test
    fun theAddedDateIsTheAbbreviatedDateOnly() {
        assertEquals("Oct 1, 2026", formatAddedDate(WalletFixtures.CREATED_AT, Locale.US, ZoneOffset.UTC))
    }
}
