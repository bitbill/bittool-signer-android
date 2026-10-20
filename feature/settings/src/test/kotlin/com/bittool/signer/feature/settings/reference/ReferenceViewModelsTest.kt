package com.bittool.signer.feature.settings.reference

import androidx.lifecycle.SavedStateHandle
import com.bittool.signer.core.data.testing.FakeReferenceDataRepository
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.feature.settings.MainDispatcherRule
import com.bittool.signer.feature.settings.SettingsTestData
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Networks, Tokens & Contracts and the network pages (activity-settings.md §4.2–§4.4, gaps.md G1.18). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val repository = FakeReferenceDataRepository(data = SettingsTestData.reference)

    private fun TestScope.page(chain: String, handle: SavedStateHandle = SavedStateHandle(mapOf(NetworkReferenceViewModel.CHAIN to chain))) =
        NetworkReferenceViewModel(handle, repository, StandardTestDispatcher(testScheduler)).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
        }

    @Test
    fun theDirectoryListsEveryRegistryNetworkWithItsCounts() = runTest {
        val viewModel = NetworksViewModel(repository)
        assertTrue(viewModel.uiState.value.loading)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("2026-09-22.1", state.registryVersion)
        assertEquals(listOf("ethereum", "tron", "gnosis"), state.networks.map { it.key })
        val ethereum = state.networks.first()
        assertEquals(3, ethereum.tokens)
        assertEquals(2, ethereum.contracts)
        assertEquals(NetworkBrand.Ethereum, ethereum.brand)
        // A network without bundled artwork (Gnosis) gets the generic logo, and has neither tokens nor contracts here.
        assertEquals(null, state.networks.last().brand)
        assertEquals(0, state.networks.last().tokens)
        assertEquals(2, state.everyChainContracts)
        assertEquals(5, state.contractCount)
        assertEquals("2026-10-09", state.contractsUpdated)
        assertEquals(1, repository.loadCount)
    }

    @Test
    fun anUnreadableLibraryLeavesTheDirectoryEmptyWithoutAnError() = runTest {
        repository.failure = IllegalStateException("broken asset")
        val viewModel = NetworksViewModel(repository)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.networks.isEmpty())
        assertEquals(0, viewModel.uiState.value.everyChainContracts)
    }

    @Test
    fun aNetworkStartsOnTokensAndOffersBothLists() = runTest {
        val state = page("evm:1").uiState.value
        assertEquals(ReferenceTitle.Network("Ethereum"), state.title)
        assertFalse(state.showsContracts)
        assertTrue(state.showsPicker)
        assertEquals(3, state.tokenCount)
        assertEquals(2, state.contractCount)
        assertEquals(listOf(SettingsTestData.usdt, SettingsTestData.usdc, SettingsTestData.weth), state.tokens)
    }

    @Test
    fun everyEvmNetworkShowsOnlyContractsWithoutAPicker() = runTest {
        // gaps.md G1.18: "All EVM networks", a row, no segmented control.
        val state = page(ReferenceData.EVERY_EVM_CHAIN).uiState.value
        assertEquals(ReferenceTitle.EveryEvmNetwork, state.title)
        assertTrue(state.showsContracts)
        assertFalse(state.showsPicker)
        assertEquals(listOf(SettingsTestData.seaport, SettingsTestData.permit2), state.contracts)
    }

    @Test
    fun theSearchFiltersAfterAShortPauseAndIsSharedByBothLists() = runTest {
        val viewModel = page("evm:1")
        viewModel.onQueryChange("tether")
        runCurrent()
        // The field shows the text at once; the list follows after the debounce.
        assertEquals("tether", viewModel.uiState.value.query)
        assertEquals(3, viewModel.uiState.value.tokens.size)
        advanceTimeBy(NetworkReferenceViewModel.SEARCH_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(SettingsTestData.usdt), viewModel.uiState.value.tokens)
        assertEquals("tether", viewModel.uiState.value.appliedQuery)

        // The same query applies to the contracts list.
        viewModel.onSegmentSelected(1)
        runCurrent()
        assertTrue(viewModel.uiState.value.showsContracts)
        assertTrue(viewModel.uiState.value.showsNoResults, "no contract on Ethereum matches \"tether\"")

        viewModel.onQueryChange("uniswap")
        advanceUntilIdle()
        assertEquals(listOf(SettingsTestData.universalRouter), viewModel.uiState.value.contracts)
        // Segment counts stay the unfiltered totals.
        assertEquals(3, viewModel.uiState.value.tokenCount)
        assertEquals(2, viewModel.uiState.value.contractCount)
    }

    @Test
    fun theSearchFieldHoldsEveryKeystrokeAtOnce() = runTest {
        // The field reads `query` directly: it must hold each edit before any coroutine runs, or a fast second
        // keystroke would be applied to the old text.
        val viewModel = page("evm:1")
        viewModel.onQueryChange("t")
        viewModel.onQueryChange("te")
        assertEquals("te", viewModel.query)
        viewModel.onQueryChange("tether")
        assertEquals("tether", viewModel.query)
        advanceUntilIdle()
        assertEquals("tether", viewModel.uiState.value.query)
        assertEquals(listOf(SettingsTestData.usdt), viewModel.uiState.value.tokens)
    }

    @Test
    fun aRestoredSearchFiltersAsSoonAsThePageLoads() = runTest {
        // §14.3 "first emission immediate": a query restored after process death does not show the unfiltered list
        // for a debounce period first.
        val handle = SavedStateHandle(mapOf(NetworkReferenceViewModel.CHAIN to "evm:1", "query" to "tether"))
        val viewModel = NetworkReferenceViewModel(handle, repository, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals("tether", viewModel.query)
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(listOf(SettingsTestData.usdt), viewModel.uiState.value.tokens)
    }

    @Test
    fun clearingTheSearchRestoresTheListImmediately() = runTest {
        val viewModel = page("evm:1")
        viewModel.onQueryChange("weth")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.tokens.size)
        viewModel.onQueryChange("")
        runCurrent()
        assertEquals(3, viewModel.uiState.value.tokens.size)
    }

    @Test
    fun theQueryAndSegmentSurviveProcessDeath() = runTest {
        val handle = SavedStateHandle(mapOf(NetworkReferenceViewModel.CHAIN to "evm:1"))
        val first = page("evm:1", handle)
        first.onQueryChange("usd")
        first.onSegmentSelected(1)
        advanceUntilIdle()
        val restored = page("evm:1", SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals("usd", restored.uiState.value.query)
        assertTrue(restored.uiState.value.showsContracts)
    }

    @Test
    fun aNetworkWithoutTokensOpensOnContracts() = runTest {
        val tronOnlyContracts = SettingsTestData.reference.copy(tokensByChain = emptyMap())
        repository.data = tronOnlyContracts
        val state = page("tron:").uiState.first { !it.loading }
        assertTrue(state.showsContracts)
        assertFalse(state.showsPicker)
        assertEquals(ReferenceTitle.Network("TRON"), state.title)
    }
}
