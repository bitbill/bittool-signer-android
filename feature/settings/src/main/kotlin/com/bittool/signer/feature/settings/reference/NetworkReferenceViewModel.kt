package com.bittool.signer.feature.settings.reference

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.coroutines.DefaultDispatcher
import com.bittool.signer.core.data.ReferenceDataRepository
import com.bittool.signer.core.model.registry.KnownContractEntry
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.core.model.registry.TokenRegistryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The title of a network page: the brand's short name verbatim, or "All EVM networks". */
@Immutable
sealed interface ReferenceTitle {
    data class Network(val shortName: String) : ReferenceTitle
    data object EveryEvmNetwork : ReferenceTitle
}

/** What one network page draws (iOS `NetworkReferenceView`). */
@Immutable
data class NetworkReferenceUiState(
    val loading: Boolean = true,
    val title: ReferenceTitle = ReferenceTitle.Network(""),
    /** Unfiltered totals: the segment labels "%d Tokens" / "%d Contracts". */
    val tokenCount: Int = 0,
    val contractCount: Int = 0,
    val showsContracts: Boolean = false,
    /** The text in the search field, as typed. */
    val query: String = "",
    /** The query the lists below were filtered with (normalised). */
    val appliedQuery: String = "",
    val tokens: List<TokenRegistryEntry> = emptyList(),
    val contracts: List<KnownContractEntry> = emptyList(),
) {
    /** The segmented control appears only when both lists have entries (a contracts-only page has none). */
    val showsPicker: Boolean get() = tokenCount > 0 && contractCount > 0

    /** The search found nothing in the visible list: the "No Results" view. */
    val showsNoResults: Boolean get() = !loading && (if (showsContracts) contracts.isEmpty() else tokens.isEmpty())
}

/**
 * One network's offline reference data (iOS `NetworkReferenceView`, activity-settings.md §4.4): its tokens and its
 * known contracts, one search field shared by both lists. The route argument `chain` is the grouping key (`evm:1`,
 * `tron:`, `evm:*`). The page starts on tokens, or on contracts when the network has no tokens. Filtering runs on the
 * default dispatcher, debounced while typing (clearing is immediate), so typing never janks (§14.3).
 */
@HiltViewModel
class NetworkReferenceViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: ReferenceDataRepository,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ViewModel() {

    private val chain: String = checkNotNull(savedStateHandle.get<String>(CHAIN)) { "chain argument missing" }

    private data class Source(
        val title: ReferenceTitle,
        val tokens: List<TokenRegistryEntry>,
        val contracts: List<KnownContractEntry>,
    )

    private data class Filtered(val query: String, val tokens: List<TokenRegistryEntry>, val contracts: List<KnownContractEntry>)

    private val source = MutableStateFlow<Source?>(null)
    private val query = MutableStateFlow(savedStateHandle.get<String>(QUERY).orEmpty())
    private val showsContracts = MutableStateFlow(savedStateHandle.get<Boolean>(SHOWS_CONTRACTS))

    private val filtered = combine(source.filterNotNull(), query.map(ReferenceSearch::normalize).distinctUntilChanged()) { s, q -> s to q }
        .debounce { (_, q) -> if (q.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }
        .mapLatest { (s, q) -> Filtered(q, ReferenceSearch.tokens(s.tokens, q), ReferenceSearch.contracts(s.contracts, q)) }
        .flowOn(default)
        .map<Filtered, Filtered?> { it }
        .onStart { emit(null) }

    val uiState: StateFlow<NetworkReferenceUiState> = combine(
        source,
        query,
        showsContracts,
        filtered,
    ) { source, query, contracts, filtered ->
        if (source == null) {
            NetworkReferenceUiState(query = query)
        } else {
            val shown = filtered ?: Filtered("", source.tokens, source.contracts)
            NetworkReferenceUiState(
                loading = false,
                title = source.title,
                tokenCount = source.tokens.size,
                contractCount = source.contracts.size,
                showsContracts = contracts ?: source.tokens.isEmpty(),
                query = query,
                appliedQuery = shown.query,
                tokens = shown.tokens,
                contracts = shown.contracts,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkReferenceUiState(query = query.value))

    init {
        viewModelScope.launch {
            val data = try {
                repository.load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ReferenceData.Empty
            }
            source.value = data.source(chain)
        }
    }

    fun onQueryChange(text: String) {
        query.value = text
        savedStateHandle[QUERY] = text
    }

    /** Selects the tokens (index 0) or contracts (index 1) list; the query is kept for both. */
    fun onSegmentSelected(index: Int) {
        val contracts = index == 1
        showsContracts.value = contracts
        savedStateHandle[SHOWS_CONTRACTS] = contracts
    }

    private fun ReferenceData.source(chain: String): Source {
        val title = if (chain == ReferenceData.EVERY_EVM_CHAIN) {
            ReferenceTitle.EveryEvmNetwork
        } else {
            ReferenceTitle.Network(networks.firstOrNull { it.chain == chain }?.shortName.orEmpty())
        }
        val tokens = if (chain == ReferenceData.EVERY_EVM_CHAIN) emptyList() else tokensByChain[chain].orEmpty()
        return Source(title, tokens, contractsByChain[chain].orEmpty())
    }

    companion object {
        /** The route argument of `NetworkReferenceRoute`. */
        const val CHAIN = "chain"
        private const val QUERY = "query"
        private const val SHOWS_CONTRACTS = "showsContracts"
        const val SEARCH_DEBOUNCE_MILLIS = 120L
    }
}
