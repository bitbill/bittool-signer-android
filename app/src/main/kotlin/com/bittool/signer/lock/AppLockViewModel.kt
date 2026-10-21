package com.bittool.signer.lock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.BiometricCancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A wallet the lock screen offers: its name only ("the fingerprint also appears in exports, so it stays behind the lock"). */
@Immutable
data class LockWallet(val id: WalletId, val name: String)

/** What the lock screen shows (iOS `AppLockView` state, Features/Lock/AppLockView.swift:6-16). */
@Immutable
data class AppLockUiState(
    /** The password form instead of the biometric actions. */
    val usePassword: Boolean = false,
    val wallets: List<LockWallet> = emptyList(),
    val selectedWallet: WalletId? = null,
    /** A wallet password is being checked (the vault decrypts; nothing is returned). */
    val busy: Boolean = false,
    /** A biometric prompt is up or being prepared. */
    val authenticating: Boolean = false,
    val biometricsAvailable: Boolean = false,
    /** iOS `error ?? access.error`: this screen's last failure, else App Lock's. Shown verbatim (a warning callout). */
    val error: UiText? = null,
) {
    val selectedName: String? get() = wallets.firstOrNull { it.id == selectedWallet }?.name
}

/** One-off requests to the password field, which lives in the composition (never in saved state). */
enum class AppLockEvent {
    /** Clear the typed password (a new lock, a new security epoch, back to biometrics). */
    ClearPassword,

    /** Put the focus back into the field (after a wrong password, iOS `passwordFocused = true`). */
    FocusPassword,
}

/**
 * The lock screen's logic (iOS `AppLockView`, AppLockView.swift:6-128; app-shell.md §11.9, §15.6–§15.7). "It only
 * restores access to the app; it never unlocks a wallet or authorizes signing."
 *
 * One instance per activity; each lock **engagement** (App Lock going from open to shown) starts from a clean state,
 * like the new iOS window per lock: biometric actions, no wallet chosen, no error, and one pending automatic biometric
 * prompt, shown as soon as the activity is resumed and App Lock has resolved (iOS `.task { authenticateBiometrics() }`).
 * Returning from a short background visit while still locked does not prompt again.
 *
 * - Biometrics unavailable: the password form at once, with the only wallet preselected (an Android improvement),
 *   also when the wallet list finishes loading after the form appeared.
 * - The prompt's "Use a Wallet Password" switches to the password form; a dismissal or a system cancel is silent.
 * - Unlocking with a password takes the typed password, which the field has already cleared ("a wrong password
 *   therefore requires retyping"), checks it through `AppAccessController.authenticate` and wipes it; a failure of the
 *   current attempt shows its message and refocuses the field. Any wallet's password opens the app.
 * - A new security epoch (background, capture) invalidates the attempt in flight and clears the field.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val access: AppAccessController,
    private val store: SignerStore,
) : ViewModel() {

    private data class Local(
        val usePassword: Boolean = false,
        val selectedWallet: WalletId? = null,
        /**
         * The person chose "Choose a wallet" (iOS `tag(nil as UUID?)`): no wallet, and the only one is not chosen for
         * them again until they come back to the form from the biometric actions.
         */
        val noWallet: Boolean = false,
        val busy: Boolean = false,
        val error: UiText? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<AppLockUiState> = combine(
        local,
        access.state,
        store.state.map { state -> state.wallets.map { LockWallet(it.id, it.name) } }.distinctUntilChanged(),
    ) { local, accessState, wallets ->
        AppLockUiState(
            usePassword = local.usePassword,
            wallets = wallets,
            selectedWallet = selection(local, wallets.map { it.id }),
            busy = local.busy,
            authenticating = accessState.isAuthenticating,
            biometricsAvailable = accessState.biometricsAvailable,
            error = local.error ?: accessState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppLockUiState())

    private val events = Channel<AppLockEvent>(Channel.BUFFERED)

    /** Field requests, in order; collect while the lock screen is composed. */
    val fieldEvents: Flow<AppLockEvent> = events.receiveAsFlow()

    /** iOS `operation = UUID()`: renewed by every attempt, epoch and engagement change; late results are dropped. */
    private var operation = 0L
    private var resumed = false
    private var autoPromptPending = false

    init {
        viewModelScope.launch {
            access.state.map { it.showsLock }.distinctUntilChanged().collect { shown -> if (shown) beginEngagement() else endEngagement() }
        }
        viewModelScope.launch {
            // App Lock resolving (the preference read) or the lock engaging later may complete the conditions.
            access.state.collect { maybeAutoPrompt() }
        }
        viewModelScope.launch {
            store.state.map { it.securityEpoch }.distinctUntilChanged().drop(1).collect {
                operation++
                local.update { it.copy(busy = false) }
                events.trySend(AppLockEvent.ClearPassword)
            }
        }
    }

    private fun beginEngagement() {
        operation++
        local.value = Local()
        events.trySend(AppLockEvent.ClearPassword)
        autoPromptPending = true
        maybeAutoPrompt()
    }

    private fun endEngagement() {
        operation++
        autoPromptPending = false
        local.value = Local()
        events.trySend(AppLockEvent.ClearPassword)
    }

    /**
     * The wallet the password is for: the person's choice while that wallet exists, otherwise on the password form the
     * only wallet — also when the list arrives after the form (a cold launch shows the lock while the wallets load) —
     * unless the person chose "Choose a wallet".
     */
    private fun selection(local: Local, wallets: List<WalletId>): WalletId? {
        if (local.noWallet) return null
        return local.selectedWallet?.takeIf { it in wallets } ?: wallets.singleOrNull()?.takeIf { local.usePassword }
    }

    /** The lock screen's `LifecycleResumeEffect`: prompts are shown only while the activity is resumed. */
    fun onResumedChanged(resumed: Boolean) {
        this.resumed = resumed
        if (resumed) maybeAutoPrompt()
    }

    private fun maybeAutoPrompt() {
        val state = access.state.value
        if (!autoPromptPending || !resumed || state.resolving || !state.isEnabled || !state.isLocked) return
        autoPromptPending = false
        authenticateBiometrics()
    }

    /** "Unlock with Biometrics" and the automatic prompt (AppLockView.swift:101-111). */
    fun authenticateBiometrics() {
        val state = access.state.value
        if (!state.biometricsAvailable) {
            usePassword()
            return
        }
        if (state.isAuthenticating) return
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                access.authenticateBiometrics()
            } catch (e: BiometricCancellationException) {
                // Only the person's own choice of a password switches the form; dismissals and system cancels are silent.
                if (e.reason == BiometricCancellationException.Reason.NegativeButton) usePassword()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                val text = e.toUiText()
                local.update { it.copy(error = text) }
            }
        }
    }

    /** "Use a Wallet Password": the form, the only wallet preselected ([selection]). */
    fun usePassword() {
        local.update { it.copy(usePassword = true, error = null) }
    }

    /**
     * "Use Biometrics Instead": back to the biometric actions, the typed password cleared. A later "Use a Wallet
     * Password" chooses the only wallet again when none is chosen (iOS `if walletID == nil, store.wallets.count == 1`).
     */
    fun useBiometrics() {
        events.trySend(AppLockEvent.ClearPassword)
        local.update { it.copy(usePassword = false, noWallet = false, error = null) }
    }

    /** A wallet from the menu. */
    fun selectWallet(id: WalletId) {
        local.update { it.copy(selectedWallet = id, noWallet = false) }
    }

    /**
     * "Choose a wallet", the menu's first item (iOS `Text("Choose a wallet").tag(nil as UUID?)`): no wallet, so
     * "Unlock" waits for one.
     */
    fun clearWallet() {
        local.update { it.copy(selectedWallet = null, noWallet = true) }
    }

    /**
     * "Unlock" / the keyboard's Go (AppLockView.swift:113-127). Takes ownership of [password] (wiped here); the caller
     * has already cleared its field. Ignored while a check runs, without a wallet or with an empty password.
     */
    fun unlock(password: SecretText) {
        val current = local.value
        val walletId = selection(current, store.state.value.wallets.map { it.id })
        if (current.busy || walletId == null || password.isEmpty()) {
            password.wipe()
            return
        }
        val token = ++operation
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                access.authenticate(walletId, password)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                if (token == operation) {
                    val text = e.toUiText()
                    local.update { it.copy(error = text) }
                    events.trySend(AppLockEvent.FocusPassword)
                }
            } finally {
                password.wipe()
                if (token == operation) local.update { it.copy(busy = false) }
            }
        }
    }
}
