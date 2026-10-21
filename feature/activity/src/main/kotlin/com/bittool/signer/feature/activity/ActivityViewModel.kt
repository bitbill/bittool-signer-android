package com.bittool.signer.feature.activity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.Tab
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Activity tab draws (iOS `ActivityTab`). */
@Immutable
data class ActivityUiState(
    /** The first wallet load has not finished: nothing is drawn yet (no "No Signatures Yet" flash). */
    val loading: Boolean = true,
    /**
     * The encrypted record could not be read (`SignerState.activityUnreadable`): an explanatory state instead of the
     * empty list, and no "Clear" (DECISIONS.md §10, iOS parity).
     */
    val unreadable: Boolean = false,
    val days: List<ActivityDay> = emptyList(),
    /** "Clear all activity?" is up. */
    val showsClearConfirmation: Boolean = false,
    /**
     * Android-only (QA L1, `SignerState.activityRestarted`): the record's key was deleted with the screen lock, so the
     * store started a new, empty record; the tab says so once, until "OK".
     */
    val restartNotice: Boolean = false,
) {
    /** iOS shows "Clear" only while there are records. */
    val canClear: Boolean get() = days.isNotEmpty()
}

/**
 * The Activity list (iOS `ActivityTab`, activity-settings.md §2.3): receipts from [SignerStore] grouped by day, the
 * "Clear" confirmation (dismissed whenever `securityEpoch` changes — background, App Lock, screen capture —
 * `ActivityTab.swift:58`) and scroll-to-top on tab reselection.
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val store: SignerStore,
    router: AppRouter,
) : ViewModel() {

    /** The `securityEpoch` the confirmation was opened under; a newer epoch hides it. */
    private val confirmationEpoch = MutableStateFlow<Long?>(null)

    private val listInputs = store.state
        .map { ListInputs.of(it) }
        .distinctUntilChanged()

    val uiState: StateFlow<ActivityUiState> = combine(listInputs, confirmationEpoch) { inputs, epoch ->
        inputs.toUiState(epoch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListInputs.of(store.state.value).toUiState(null))

    /** Emits when the Activity tab is tapped again while shown (the list scrolls back to the top). */
    val scrollToTop: Flow<Unit> = router.reselections.filter { it == Tab.Activity }.map { }

    fun onClearClicked() {
        confirmationEpoch.value = store.state.value.securityEpoch
    }

    fun onClearDismissed() {
        confirmationEpoch.value = null
    }

    /** "OK" of the notice that the record was started again (QA L1): it is not shown again, also after a relaunch. */
    fun onRestartNoticeDismissed() {
        viewModelScope.launch { store.acknowledgeActivityRestart() }
    }

    /** "Clear Activity": the store saves an empty journal; a failed write is reported by the store's own alert. */
    fun onClearConfirmed() {
        if (confirmationEpoch.value != store.state.value.securityEpoch) return
        confirmationEpoch.value = null
        viewModelScope.launch {
            try {
                store.clearHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toUiText()?.let(store::setError)
            }
        }
    }

    /** The part of [SignerState] the list depends on, so unrelated store changes (signing, scanning) are not regrouped. */
    private data class ListInputs(
        val loading: Boolean,
        val unreadable: Boolean,
        val entries: List<ActivityEntry>,
        val securityEpoch: Long,
        val restarted: Boolean,
    ) {
        fun toUiState(confirmationEpoch: Long?): ActivityUiState {
            val days = ActivityGrouping.sections(entries, ZoneId.systemDefault())
            return ActivityUiState(
                loading = loading,
                unreadable = unreadable && entries.isEmpty(),
                days = days,
                showsClearConfirmation = confirmationEpoch != null && confirmationEpoch == securityEpoch && days.isNotEmpty(),
                restartNotice = restarted && !(unreadable && entries.isEmpty()),
            )
        }

        companion object {
            fun of(state: SignerState) = ListInputs(
                loading = state.isLoading,
                unreadable = state.activityUnreadable,
                entries = state.receipts.map { receipt ->
                    ActivityEntry(
                        id = receipt.id,
                        title = receipt.title,
                        summary = receipt.summary.orEmpty(),
                        wallet = WalletLabel.of(receipt, state),
                        dateMillis = receipt.date,
                        chain = receipt.chain,
                        chainId = receipt.chainId,
                    )
                },
                securityEpoch = state.securityEpoch,
                restarted = state.activityRestarted,
            )
        }
    }
}
