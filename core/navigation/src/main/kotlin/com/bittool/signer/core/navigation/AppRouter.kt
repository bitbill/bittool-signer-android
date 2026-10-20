package com.bittool.signer.core.navigation

import dagger.hilt.android.scopes.ActivityRetainedScoped
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** The four tabs, in bar order (iOS `AppRouter.Tab`, app-shell.md §8). [graph] is the tab's nested graph route. */
enum class Tab(val graph: SignerRoute, val root: SignerRoute) {
    Wallets(WalletsGraph, WalletsRoute),
    Scan(ScanGraph, ScanRoute),
    Activity(ActivityGraph, ActivityRoute),
    Settings(SettingsGraph, SettingsRoute),
}

/** What a setup presentation creates or restores (iOS `SetupIntent`). */
enum class SetupIntent {
    /** Create a new recovery phrase. */
    Create,
    /** Import an existing recovery phrase. */
    Restore,
}

/**
 * One setup presentation (iOS `SetupPresentation`): a fresh [id] per `startSetup`, so every presentation gets a fresh
 * setup state holder (ViewModel store keyed by [id]). Never saved: process death discards the draft like iOS.
 */
data class SetupPresentation(val id: Long, val intent: SetupIntent, val showsIntroduction: Boolean)

/** A change of the Wallets tab's back stack that `:app`'s NavHost glue executes (iOS `walletPath` assignments). */
sealed interface WalletStackRequest {
    /** Select Wallets, then make [route] the only page above `WalletsRoute` (iOS `walletPath = [route]`). */
    data class Replace(val route: WalletRoute) : WalletStackRequest

    /** Pop the Wallets stack to `WalletsRoute` (iOS `walletPath = []`); other tabs' stacks are untouched. */
    data object Reset : WalletStackRequest
}

/**
 * Cross-screen navigation state (port of iOS `Features/Main/AppRouter.swift`, app-shell.md §9). "UI navigation only.
 * Nothing here selects a signer or grants authorization: routes carry an explicit wallet ID solely to show that
 * wallet's public data."
 *
 * Activity-retained (survives configuration changes, one per activity). Main-thread only. The NavHost glue in `:app`
 * observes [tab] (navigates to the tab graph with save/restore state), [walletStackRequests] (executes them in order),
 * [setup] (shows the setup overlay) and reports the visible tab back with [onTabShown] after system Back.
 */
@ActivityRetainedScoped
class AppRouter @Inject constructor() {
    private val _tab = MutableStateFlow(Tab.Wallets)

    /** The selected tab. Default Wallets; not persisted. */
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _reselections = MutableSharedFlow<Tab>(extraBufferCapacity = 1)

    /** One-shot: the user tapped the already selected tab (pop that tab's stack to its root, then scroll to top). */
    val reselections: SharedFlow<Tab> = _reselections.asSharedFlow()

    private val _scanRequest = MutableStateFlow(0)

    /**
     * Bumped by [startScanning]. A token rather than a flag, so asking twice in a row works and nothing has to be reset.
     * The Scan home observes it and calls [consumeScanRequest] on appearance and on change.
     */
    val scanRequest: StateFlow<Int> = _scanRequest.asStateFlow()

    /** The last [scanRequest] the Scan tab acted on. Deliberately not observable: changing it must redraw nothing. */
    private var handledScanRequest = 0

    private val _setup = MutableStateFlow<SetupPresentation?>(null)

    /** The setup flow being presented above the stages, or null. */
    val setup: StateFlow<SetupPresentation?> = _setup.asStateFlow()

    /** Opened once the setup overlay has fully disappeared (pushing during the exit animation can stick). */
    private var routeAfterSetup: WalletRoute? = null

    private val walletRequests = Channel<WalletStackRequest>(Channel.UNLIMITED)

    /** Wallets-stack changes in order; buffered while no collector is active (configuration change). */
    val walletStackRequests: Flow<WalletStackRequest> = walletRequests.receiveAsFlow()

    private val setupIds = AtomicLong()

    /** Selects [tab] (a tab bar tap, or programmatic: a new review selects Scan). */
    fun selectTab(tab: Tab) {
        _tab.value = tab
    }

    /** The tab bar's tap handler: reselecting the current tab emits a reselection, otherwise selects. */
    fun onTabClicked(tab: Tab) {
        if (_tab.value == tab) _reselections.tryEmit(tab) else _tab.value = tab
    }

    /** The NavHost glue reports the tab whose graph is now visible (e.g. system Back returned to Wallets). */
    fun onTabShown(tab: Tab) {
        _tab.value = tab
    }

    /** iOS `startSetup`: a new presentation; the introduction shows only for the first wallet until completed once. */
    fun startSetup(intent: SetupIntent, firstWallet: Boolean, introductionCompleted: Boolean) {
        routeAfterSetup = null
        _setup.value = SetupPresentation(setupIds.incrementAndGet(), intent, firstWallet && !introductionCompleted)
    }

    /** iOS `finishSetup(opening:)`: closes setup; [route] (e.g. `ConnectRoute.walletLevel`) opens after it disappeared. */
    fun finishSetup(opening: WalletRoute?) {
        routeAfterSetup = opening
        _setup.value = null
    }

    /** Closes setup without opening anything (Cancel, discard, Back on the first step). */
    fun cancelSetup() {
        routeAfterSetup = null
        _setup.value = null
    }

    /** iOS `setupDidDismiss`: call when the setup overlay's exit animation has finished. */
    fun setupDidDismiss() {
        val route = routeAfterSetup ?: return
        routeAfterSetup = null
        open(route)
    }

    /** iOS `startScanning`: select Scan and ask it to open the camera once. */
    fun startScanning() {
        _tab.value = Tab.Scan
        _scanRequest.value += 1
    }

    /**
     * True once per [startScanning], then false until the next one. Consumed even while a review is showing, so the
     * camera never pops open later. Selecting the Scan tab by hand never opens the camera.
     */
    fun consumeScanRequest(): Boolean {
        val current = _scanRequest.value
        if (current == handledScanRequest) return false
        handledScanRequest = current
        return true
    }

    /** iOS `open(route)`: Wallets tab with [route] as its only pushed page. */
    fun open(route: WalletRoute) {
        _tab.value = Tab.Wallets
        walletRequests.trySend(WalletStackRequest.Replace(route))
    }

    /** iOS `resetNavigation()`: empty the Wallets stack (wallet deleted; count reached 0 also selects Wallets). */
    fun resetNavigation() {
        walletRequests.trySend(WalletStackRequest.Reset)
    }
}
