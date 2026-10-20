package com.bittool.signer.feature.sign.tab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.Tab
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Which page the Scan tab shows (iOS `ScanTab`, `Features/Sign/ScanTab.swift:88-106`). */
enum class ScanStage {
    Home,
    Review,
    Signature,
    ;

    /**
     * The page to draw: this stage, except that the review stays while "Match Again" runs — the store clears the
     * request before reviewing the same one again, which would otherwise flash Home for an engine call.
     */
    fun shown(retrying: Boolean): ScanStage = if (retrying && this == Home) Review else this

    companion object {
        /** A finished signature first, then a request under review, else Home (iOS order). */
        fun of(state: SignerState): ScanStage = when {
            state.hasSignedResponse -> Signature
            state.review != null -> Review
            else -> Home
        }
    }
}

/**
 * The Scan tab container (inventory row 45): the page derived from `SignerState`, and the "open the camera" requests
 * made elsewhere (the Wallets toolbar "Scan", `AppRouter.startScanning`).
 *
 * The request token is consumed whenever the tab appears or the token changes (iOS `openCameraIfAsked`,
 * `ScanTab.swift:166-170`), also while a request is being reviewed or a signature is shown — then the camera does not
 * open and the request is not kept for later, so finishing a signature never pops the camera open (iOS regression #5,
 * `ScanRequestRouterTests`). The handled marker lives in [AppRouter], never here: this ViewModel outlives Home, but the
 * rule must not depend on that.
 */
@HiltViewModel
class ScanTabViewModel @Inject constructor(
    private val store: SignerStore,
    private val router: AppRouter,
) : ViewModel() {

    /**
     * Kept current even while nothing collects it ([SharingStarted.Eagerly], an in-memory map of the store). The Scan
     * page leaves composition whenever the camera covers it or another tab is shown; with `WhileSubscribed` the value
     * would freeze there, so a page that comes back (the camera closing on a finished scan, a return to the tab)
     * would first draw the stage it left — Home under the closing camera, cross-fading into the review — instead of
     * the review "already rendered underneath" (scan-sign.md §7).
     */
    val stage: StateFlow<ScanStage> = store.state
        .map(ScanStage::of)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanStage.of(store.state.value))

    /** The router's request token; the screen reconciles it on appearance and on every change. */
    val scanRequest: StateFlow<Int> get() = router.scanRequest

    /** The Scan tab was tapped while selected: scroll the visible page to the top. */
    val reselections: Flow<Tab> = router.reselections.filter { it == Tab.Scan }

    /** True when the camera should open now for a pending request (consumed either way). */
    fun consumeScanRequest(): Boolean {
        val asked = router.consumeScanRequest()
        return asked && store.state.value.review == null && !store.state.value.hasSignedResponse
    }
}
