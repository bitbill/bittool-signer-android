package com.bittool.signer.feature.settings.reference

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.ReferenceDataRepository
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.registry.ReferenceData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One registry network row (iOS `TokenDirectoryView` row). */
@Immutable
data class NetworkRowModel(
    /** Registry slug: the test tag `network.<key>`. */
    val key: String,
    /** Grouping key (`evm:1`, `tron:`): the route argument. */
    val chain: String,
    /** Verbatim registry name ("BNB Smart Chain"). */
    val name: String,
    val nativeSymbol: String,
    val brand: NetworkBrand?,
    /** Bundled tokens of this network (the grouped list, as iOS counts them). */
    val tokens: Int,
    val contracts: Int,
)

/** What "Networks, Tokens & Contracts" draws. */
@Immutable
data class NetworksUiState(
    val loading: Boolean = true,
    val registryVersion: String = "",
    val networks: List<NetworkRowModel> = emptyList(),
    /** Contracts listed for every EVM network at once (`evm:*`). */
    val everyChainContracts: Int = 0,
    val contractCount: Int = 0,
    /** The library's `updated` ISO date, verbatim. */
    val contractsUpdated: String = "",
)

/**
 * "Networks, Tokens & Contracts" (iOS `TokenDirectoryView`, activity-settings.md §4.2–§4.3): the bundled registry and
 * known-contract library, decoded once per process by [ReferenceDataRepository] off the main thread. An unreadable file
 * leaves its half empty (no error UI, as iOS).
 */
@HiltViewModel
class NetworksViewModel @Inject constructor(private val repository: ReferenceDataRepository) : ViewModel() {
    private val state = MutableStateFlow(NetworksUiState())
    val uiState: StateFlow<NetworksUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val data = try {
                repository.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReferenceData.Empty
            }
            state.value = data.toUiState()
        }
    }

    private fun ReferenceData.toUiState() = NetworksUiState(
        loading = false,
        registryVersion = registryVersion,
        networks = networks.map { network ->
            NetworkRowModel(
                key = network.key,
                chain = network.chain,
                name = network.name,
                nativeSymbol = network.nativeSymbol,
                brand = network.brand,
                tokens = tokensByChain[network.chain]?.size ?: 0,
                contracts = contractsByChain[network.chain]?.size ?: 0,
            )
        },
        everyChainContracts = everyChainContracts.size,
        contractCount = contractCount,
        contractsUpdated = contractsUpdated,
    )
}
