package com.bittool.signer.feature.wallets.addresses

import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.model.UiText
import com.bittool.signer.feature.wallets.MainDispatcherRule
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.observe
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The Addresses paging state machine (wallets-pairing.md §6.1, iOS `AddressesView.swift:106-140`). */
class AddressesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun pagedStore(pageSize: (start: Long, count: Int) -> Int = { _, count -> count }): FakeSignerStore =
        WalletFixtures.store().apply {
            onAddresses = { _, chain, _, start, count ->
                if (chain == Chain.Solana) WalletFixtures.solanaAccounts else WalletFixtures.accounts(chain, start, pageSize(start, count))
            }
        }

    private fun viewModel(store: FakeSignerStore, chain: String? = null) =
        AddressesViewModel(store, WalletFixtures.args(chain = chain))

    @Test
    fun theFirstPageIsTwentyBitcoinNativeReceiveAddresses() = runTest(main.dispatcher) {
        val store = pagedStore()
        val state = observe(viewModel(store).uiState)
        advanceUntilIdle()
        assertEquals(
            listOf(Call.Addresses(WalletFixtures.readyId, Chain.Bitcoin, "native", 0, 20)),
            store.calls<Call.Addresses>(),
        )
        assertTrue(state.value.loaded)
        assertEquals(20, state.value.accounts.size)
        assertEquals(Chain.Bitcoin, state.value.chain)
        assertEquals("native", state.value.btcProfile)
    }

    @Test
    fun theLastRowLoadsTheNextPageFromTheCurrentCount() = runTest(main.dispatcher) {
        val next = CompletableDeferred<Unit>()
        val store = WalletFixtures.store().apply {
            onAddresses = { _, chain, _, start, count ->
                if (start > 0) next.await()
                WalletFixtures.accounts(chain, start, count)
            }
        }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.loadMore()
        assertTrue(state.value.loadingMore, "the spinner row shows while the next page loads")
        next.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0L, 20L), store.calls<Call.Addresses>().map { it.start })
        assertEquals(40, state.value.accounts.size)
        assertEquals((0L until 40L).toList(), state.value.accounts.map(PublicAccount::index))
        assertFalse(state.value.loadingMore)
    }

    @Test
    fun aShortPageEndsPaging() = runTest(main.dispatcher) {
        val store = pagedStore { start, count -> if (start == 0L) count else 7 }
        val vm = viewModel(store)
        observe(vm.uiState)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0L, 20L), store.calls<Call.Addresses>().map { it.start }, "no request after a page of fewer than 20")
    }

    @Test
    fun loadMoreIsIgnoredWhileAPageIsLoading() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val store = WalletFixtures.store().apply {
            onAddresses = { _, chain, _, start, count ->
                if (start > 0) gate.await()
                WalletFixtures.accounts(chain, start, count)
            }
        }
        val vm = viewModel(store)
        observe(vm.uiState)
        advanceUntilIdle()
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()
        vm.loadMore()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0L, 20L), store.calls<Call.Addresses>().map { it.start })
    }

    @Test
    fun evmAndTronUseTheStandardProfileAndSolanaAllThreePresetsWithoutPaging() = runTest(main.dispatcher) {
        val store = pagedStore()
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.selectChain(Chain.Evm)
        advanceUntilIdle()
        vm.selectChain(Chain.Tron)
        advanceUntilIdle()
        vm.selectChain(Chain.Solana)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val calls = store.calls<Call.Addresses>()
        assertEquals(
            listOf(
                Call.Addresses(WalletFixtures.readyId, Chain.Bitcoin, "native", 0, 20),
                Call.Addresses(WalletFixtures.readyId, Chain.Evm, "standard", 0, 20),
                Call.Addresses(WalletFixtures.readyId, Chain.Tron, "standard", 0, 20),
                Call.Addresses(WalletFixtures.readyId, Chain.Solana, "", 0, 3),
            ),
            calls,
            "Solana never pages",
        )
        assertEquals(WalletFixtures.solanaAccounts, state.value.accounts)
    }

    @Test
    fun aNewKeyHidesTheOldRowsAndDropsALateResultOfTheOldKey() = runTest(main.dispatcher) {
        val slowBitcoin = CompletableDeferred<Unit>()
        val store = WalletFixtures.store().apply {
            onAddresses = { _, chain, profile, start, count ->
                if (chain == Chain.Bitcoin && profile == "taproot") slowBitcoin.await()
                WalletFixtures.accounts(chain, start, count)
            }
        }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.selectBitcoinProfile("taproot")
        advanceUntilIdle()
        assertFalse(state.value.loaded, "rows of the previous key are hidden while the new key loads")
        assertTrue(state.value.accounts.isEmpty())
        vm.selectChain(Chain.Evm)
        advanceUntilIdle()
        slowBitcoin.complete(Unit)
        advanceUntilIdle()
        assertEquals(Chain.Evm, state.value.chain)
        assertTrue(state.value.accounts.all { it.chain == Chain.Evm }, "the cancelled taproot load never shows")
    }

    @Test
    fun aFailedFirstPageShowsTheMessageVerbatim() = runTest(main.dispatcher) {
        val store = WalletFixtures.store().apply {
            onAddresses = { _, _, _, _, _ -> throw IllegalStateException("Invalid address page") }
        }
        val state = observe(viewModel(store).uiState)
        advanceUntilIdle()
        assertFalse(state.value.loaded)
        assertEquals(UiText.Verbatim("Invalid address page"), state.value.error)
    }

    @Test
    fun aStoreMessageKeepsItsTranslation() = runTest(main.dispatcher) {
        val store = WalletFixtures.store().apply {
            onAddresses = { _, _, _, _, _ -> throw AppIssue("Wallet setup required.") }
        }
        val state = observe(viewModel(store).uiState)
        advanceUntilIdle()
        assertEquals(UiText.Ios("Wallet setup required."), state.value.error)
    }

    @Test
    fun aFailedNextPageStopsPagingSilently() = runTest(main.dispatcher) {
        val store = WalletFixtures.store().apply {
            onAddresses = { _, chain, _, start, count ->
                if (start > 0) throw IllegalStateException("boom")
                WalletFixtures.accounts(chain, start, count)
            }
        }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertNull(state.value.error, "no message for a failed next page")
        assertEquals(20, state.value.accounts.size)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(2, store.calls<Call.Addresses>().size, "paging ended")
    }

    @Test
    fun theLimitIsTwoToTheThirtyFirstAsALong() {
        assertEquals(2_147_483_648L, AddressesViewModel.limit(Chain.Bitcoin))
        assertEquals(2_147_483_648L, AddressesViewModel.limit(Chain.Evm))
        assertEquals(3L, AddressesViewModel.limit(Chain.Solana))
    }

    @Test
    fun theRouteChainIsTheStartingChainAndTheAddressSheetOpensAndCloses() = runTest(main.dispatcher) {
        val store = pagedStore()
        val vm = viewModel(store, chain = "tron")
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertEquals(Chain.Tron, state.value.chain)
        val account = state.value.accounts.first()
        vm.showAddress(account)
        assertEquals(account, state.value.selected)
        vm.dismissAddress()
        assertNull(state.value.selected)
    }

    @Test
    fun aDeletedWalletClosesThePage() = runTest(main.dispatcher) {
        val store = pagedStore()
        val state = observe(viewModel(store).uiState)
        advanceUntilIdle()
        assertFalse(state.value.gone)
        store.setState { it.copy(wallets = emptyList()) }
        advanceUntilIdle()
        assertTrue(state.value.gone)
    }
}
