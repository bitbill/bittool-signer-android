package com.bittool.signer.core.security.testing

import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppAccessState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A scriptable [AppAccessController] for JVM tests of features and the shell: it records every call and applies the
 * same visible state changes as the real controller on success (unlock, enable/disable, wallet count 0 disables),
 * while each operation's outcome is chosen by the test. It keeps no password: [passwordUnlocks] records wallet ids
 * only. Not used by production code.
 *
 * Starts resolved, unlocked, App Lock off and biometrics available, unless [initial] says otherwise; [mutableState]
 * lets a test set any state directly (e.g. `isLocked = true` to show the lock).
 */
class FakeAppAccessController(
    initial: AppAccessState = AppAccessState(
        resolving = false,
        isEnabled = false,
        isLocked = false,
        biometricsAvailable = true,
    ),
) : AppAccessController {

    val mutableState: MutableStateFlow<AppAccessState> = MutableStateFlow(initial)
    override val state: StateFlow<AppAccessState> = mutableState.asStateFlow()

    /** Thrown by [setEnabled] (after recording) when non-null; a non-cancellation error is also published. */
    var setEnabledFailure: Throwable? = null

    /** Thrown by [authenticateBiometrics] when non-null; a non-cancellation error is also published. */
    var biometricFailure: Throwable? = null

    /** Thrown by [authenticate] when non-null (e.g. `VaultError.AuthenticationFailed()`); also published. */
    var passwordFailure: Throwable? = null

    /** Thrown by [confirmSensitiveAction] when non-null (a `CancellationException` = the person dismissed it). */
    var sensitiveActionFailure: Throwable? = null

    val walletCounts: MutableList<Int> = mutableListOf()
    val setEnabledCalls: MutableList<Boolean> = mutableListOf()
    var biometricUnlockCalls: Int = 0
        private set
    val passwordUnlocks: MutableList<WalletId> = mutableListOf()
    val sensitiveActionReasons: MutableList<UiText> = mutableListOf()
    var backgroundCalls: Int = 0
        private set
    var foregroundCalls: Int = 0
        private set
    var clearErrorCalls: Int = 0
        private set

    override fun updateWalletCount(count: Int) {
        walletCounts += count
        if (count <= 0) mutableState.update { it.copy(isEnabled = false, isLocked = false, isAuthenticating = false) }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        setEnabledCalls += enabled
        fail(setEnabledFailure)
        mutableState.update { it.copy(isEnabled = enabled, isLocked = false) }
    }

    override suspend fun authenticateBiometrics() {
        biometricUnlockCalls += 1
        fail(biometricFailure)
        mutableState.update { it.copy(isLocked = false, error = null) }
    }

    override suspend fun authenticate(walletId: WalletId, password: SecretText) {
        passwordUnlocks += walletId
        fail(passwordFailure)
        mutableState.update { it.copy(isLocked = false, error = null) }
    }

    override suspend fun confirmSensitiveAction(reason: UiText) {
        sensitiveActionReasons += reason
        sensitiveActionFailure?.let { throw it }
    }

    override fun didEnterBackground() {
        backgroundCalls += 1
    }

    override fun willEnterForeground() {
        foregroundCalls += 1
    }

    override fun clearError() {
        clearErrorCalls += 1
        mutableState.update { it.copy(error = null) }
    }

    /** Sets the published state to locked (App Lock on), as a cold launch or a long background visit does. */
    fun lock() {
        mutableState.update { it.copy(resolving = false, isEnabled = true, isLocked = true) }
    }

    private fun fail(error: Throwable?) {
        error ?: return
        if (error !is CancellationException) mutableState.update { it.copy(error = error.toUiText()) }
        throw error
    }
}
