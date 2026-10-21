package com.bittool.signer.root

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.appearance.AppearanceController
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.ONBOARDING_VERSION
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.SetupPresentation
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.core.navigation.WalletRoute
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.ScenePhase
import com.bittool.signer.core.security.WindowGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The root stage (iOS `RootView.Stage`, RootView.swift:13-18; app-shell.md §4.1). */
enum class RootStage {
    /** The first wallet-list load is running: `LaunchView` continues the splash. */
    Loading,

    /** The last load failed. "A failed wallet-list load is not treated as an empty list": never Welcome. */
    Failed,

    /** Loaded, no wallet: Welcome. */
    Welcome,

    /** Wallets exist: the four tabs. */
    Main,
}

/** iOS `stage` from the store. */
fun rootStage(state: SignerState): RootStage = when {
    state.isLoading -> RootStage.Loading
    !state.walletLoadSucceeded -> RootStage.Failed
    state.wallets.isEmpty() -> RootStage.Welcome
    else -> RootStage.Main
}

/** Everything the root composition draws (app-shell.md §4, ARCHITECTURE §2.1 layers). */
@Immutable
data class RootUiState(
    val stage: RootStage = RootStage.Loading,
    val appearance: Appearance = Appearance.System,
    /** The setup overlay (layer 2), or null. */
    val setup: SetupPresentation? = null,
    /** App Lock is up (or still resolving): the root content is hidden, the lock window covers it. */
    val locked: Boolean = true,
    /** The privacy cover (layer 3): the activity is not resumed, or the screen is recorded / mirrored. */
    val coverVisible: Boolean = true,
    /** The screen is recorded or mirrored (the cover says why). */
    val captured: Boolean = false,
    /** New dialogs and sheets may appear (mirrors `WindowGate`, ARCHITECTURE §1.10). */
    val gateOpen: Boolean = false,
    /** "Unable to Continue" (layer 4), or null. */
    val error: UiText? = null,
    /** The failure stage's "Try Again" is reloading. */
    val retrying: Boolean = false,
    /** The Scan tab's badge "1": a review waits and is not signed yet. */
    val showsScanBadge: Boolean = false,
)

/**
 * The root of the app (iOS `RootView` + the scene-phase handling of `BitToolSignerApp`, app-shell.md §4, §10;
 * ARCHITECTURE §2.1): the stage, the layers above it, and every reaction RootView has, exactly:
 *
 * | Trigger | Reaction |
 * |---|---|
 * | the wallet list finished loading successfully (first load, a retry) | `AppAccessController.updateWalletCount(count)` |
 * | the wallet count changes while loaded | the same; when it reaches 0 also Wallets tab + Wallets stack reset |
 * | `navigationResetEpoch` changes (a wallet was deleted) | Wallets stack reset |
 * | a new `review.requestHash` | the Scan tab |
 * | App Lock engages, or screen capture starts | `SignerStore.suspendSession()` |
 * | resumed ∧ unlocked ∧ not captured ∧ stage Main changes | `setConnectPrefetchAllowed` (also initially) |
 * | lock or capture changes | the window gate: open only when unlocked and not captured |
 * | `onStart` / `onStop` (not a configuration change) | [onEnterForeground] (+ `SignerStore.refreshDeviceKeys`, QA L4) / [onEnterBackground] |
 *
 * Lives as long as the activity's `ViewModelStore` (configuration changes included); every collector runs on the main
 * thread (`viewModelScope`, `Main.immediate`).
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val store: SignerStore,
    private val access: AppAccessController,
    private val preferences: SignerPreferences,
    private val lifecycle: AppLifecycleMonitor,
    private val windowGate: WindowGate,
    private val appearanceController: AppearanceController,
    /** The activity's router: the NavHost glue and the setup overlay use it too. */
    val router: AppRouter,
) : ViewModel() {

    private val retrying = MutableStateFlow(false)

    val uiState: StateFlow<RootUiState> = combine(
        listOf<Flow<Any?>>(
            store.state,
            access.state,
            lifecycle.phase,
            lifecycle.isCaptured,
            router.setup,
            retrying,
            windowGate.isOpen,
            appearanceController.appearance,
        ),
    ) { values -> uiState(values) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, snapshot())

    init {
        followWalletCount()
        viewModelScope.launch {
            store.state.map { it.navigationResetEpoch }.distinctUntilChanged().drop(1).collect { router.resetNavigation() }
        }
        viewModelScope.launch {
            // iOS `.onChange(of: store.review?.requestHash)`: a change to a non-nil hash (paste, file, camera).
            store.state.map { it.review?.requestHash }.distinctUntilChanged().drop(1).collect { hash ->
                if (hash != null) router.selectTab(Tab.Scan)
            }
        }
        viewModelScope.launch {
            access.state.map { it.isLocked }.distinctUntilChanged().drop(1).filter { it }.collect { store.suspendSession() }
        }
        viewModelScope.launch {
            lifecycle.isCaptured.drop(1).filter { it }.collect { store.suspendSession() }
        }
        viewModelScope.launch {
            combine(lifecycle.phase, access.state, lifecycle.isCaptured, store.state) { phase, accessState, captured, state ->
                phase == ScenePhase.Active && !accessState.showsLock && !captured && rootStage(state) == RootStage.Main
            }.distinctUntilChanged().collect(store::setConnectPrefetchAllowed)
        }
        viewModelScope.launch {
            combine(access.state, lifecycle.isCaptured) { accessState, captured -> !accessState.showsLock && !captured }
                .distinctUntilChanged()
                .collect(windowGate::setOpen)
        }
    }

    /**
     * RootView.swift:47-57: the wallet count reaches App Lock only after a successful load (never for the loading
     * placeholder or a failed load); when it drops to 0 the app returns to Welcome with the Wallets tab selected and an
     * empty Wallets stack.
     */
    private fun followWalletCount() = viewModelScope.launch {
        var loaded = false
        var lastCount: Int? = null
        store.state.map { WalletCount(it.isLoading, it.walletLoadSucceeded, it.wallets.size) }.distinctUntilChanged().collect { key ->
            if (key.loading || !key.succeeded) {
                loaded = false
                return@collect
            }
            val previous = lastCount
            if (!loaded || previous != key.count) access.updateWalletCount(key.count)
            if (previous != null && previous != key.count && key.count == 0) {
                router.selectTab(Tab.Wallets)
                router.resetNavigation()
            }
            loaded = true
            lastCount = key.count
        }
    }

    private data class WalletCount(val loading: Boolean, val succeeded: Boolean, val count: Int)

    // ---------------------------------------------------------------------------------------------------------------
    // Lifecycle (MainActivity)

    /**
     * `onStart`: App Lock's grace decision, before the first frame (iOS `willEnterForeground`). Android-only (QA L4): the
     * store then looks at the lock screen and the device keys again, since a screen lock removed or set in Settings
     * meanwhile changes which wallets can still be unlocked.
     */
    fun onEnterForeground() {
        access.willEnterForeground()
        viewModelScope.launch { runCatchingCancellable { store.refreshDeviceKeys() } }
    }

    /**
     * `onStop` without a configuration change: "The app revokes authorization immediately on entering the background,
     * regardless of App Lock's grace period" — `suspendSession()` first, then App Lock records the time (iOS order,
     * BitToolSignerApp.swift:41).
     */
    fun onEnterBackground() {
        store.suspendSession()
        access.didEnterBackground()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Setup, Welcome, failure, alert

    /** Welcome's Create / Import (WelcomeView.swift:111-113): the introduction only for the first wallet, once. */
    fun startSetup(intent: SetupIntent) {
        router.startSetup(
            intent = intent,
            firstWallet = store.state.value.wallets.isEmpty(),
            introductionCompleted = preferences.values.value?.introductionCompleted ?: false,
        )
    }

    /** "Get Started" on the last introduction page (WalletSetupFlow.swift:36-39). Cancel never records it. */
    fun onIntroductionCompleted() {
        viewModelScope.launch {
            runCatchingCancellable { preferences.setOnboardingVersionCompleted(ONBOARDING_VERSION) }
                .onFailure { Log.w(TAG, it) { "The introduction could not be marked as completed" } }
        }
    }

    fun finishSetup(opening: WalletRoute?) = router.finishSetup(opening)

    fun cancelSetup() = router.cancelSetup()

    /** The overlay's exit animation has finished: open the route "Pair a Wallet" asked for (AppRouter.swift:53-57). */
    fun setupDidDismiss() = router.setupDidDismiss()

    /** "OK" of "Unable to Continue". */
    fun dismissError() = store.setError(null)

    /** "Try Again" on "Wallets Unavailable" (RootView.swift:104-106). */
    fun retryLoad() {
        if (retrying.value) return
        retrying.value = true
        viewModelScope.launch {
            try {
                // A failure is published by the store itself (the stage stays Failed, "Unable to Continue" shows).
                runCatchingCancellable { store.reload() }.onFailure { Log.w(TAG, it) { "Reload failed" } }
            } finally {
                retrying.value = false
            }
        }
    }

    private fun snapshot(): RootUiState = uiState(
        arrayOf(
            store.state.value,
            access.state.value,
            lifecycle.phase.value,
            lifecycle.isCaptured.value,
            router.setup.value,
            retrying.value,
            windowGate.isOpen.value,
            appearanceController.appearance.value,
        ),
    )

    private fun uiState(values: Array<Any?>): RootUiState {
        val state = values[0] as SignerState
        val accessState = values[1] as AppAccessState
        val phase = values[2] as ScenePhase
        val captured = values[3] as Boolean
        return RootUiState(
            stage = rootStage(state),
            appearance = values[7] as Appearance,
            setup = values[4] as SetupPresentation?,
            locked = accessState.showsLock,
            coverVisible = phase != ScenePhase.Active || captured,
            captured = captured,
            gateOpen = values[6] as Boolean,
            error = state.error,
            retrying = values[5] as Boolean,
            showsScanBadge = state.showsScanBadge,
        )
    }

    private companion object {
        const val TAG = "Root"
    }
}
