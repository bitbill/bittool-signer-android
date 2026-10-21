package com.bittool.signer.core.security

import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import kotlinx.coroutines.flow.StateFlow

/**
 * App Lock state (app-shell.md §11.2–11.3). "It protects app access only, never signing."
 */
data class AppAccessState(
    /** The `appLockEnabled.v1` preference has not been read yet: treated as locked (fail closed; keep the splash up). */
    val resolving: Boolean = true,
    val isEnabled: Boolean = false,
    /** Cold launch with App Lock on starts locked; no authenticated state is ever persisted. */
    val isLocked: Boolean = true,
    val isAuthenticating: Boolean = false,
    /** `BiometricManager.canAuthenticate(BIOMETRIC_WEAK) == BIOMETRIC_SUCCESS`, refreshed on every foreground. */
    val biometricsAvailable: Boolean = false,
    /** The last non-cancellation authentication error, shown on the lock screen (warning callout). */
    val error: UiText? = null,
) {
    /** What the lock layer shows: locked, or still resolving the preference. */
    val showsLock: Boolean get() = resolving || isLocked
}

/**
 * Port of iOS `AppAccessController` (app-shell.md §11, AppAccessTests): "authenticates access to public app pages
 * only. It never derives accounts, returns a secret, chooses a signer, or authorizes signing." Implemented by
 * W1-security as a `@Singleton` with an injected `MonotonicClock`, `BiometricAuthenticator`, `WalletVault` and
 * `SignerPreferences`; main thread only.
 *
 * Grace rule: stay unlocked after a background visit iff `since != null && now >= since && now - since <
 * [BACKGROUND_GRACE_MILLIS]` on `SystemClock.elapsedRealtime()`; otherwise lock (a clock going backwards locks).
 * Locking renews nothing in the store by itself: `:app` calls `SignerStore.suspendSession()` when [AppAccessState.isLocked]
 * becomes true.
 */
interface AppAccessController {
    val state: StateFlow<AppAccessState>

    /**
     * Call after the wallet list finished loading successfully (never for the loading placeholder or a failed load).
     * A count of 0 cancels authentication, disables App Lock, unlocks and persists `false` (no lock no password opens).
     */
    fun updateWalletCount(count: Int)

    /**
     * Turns App Lock on or off; a no-op when [enabled] equals the current value. Refused while locked or in the
     * background ("Authenticate app access before changing App Lock."); enabling needs a wallet ("Create a wallet before
     * enabling App Lock.") and a successful biometric prompt (title "Lock BitTool Signer", negative "Cancel"), after
     * which the wallet count is checked again (0 → `CancellationException`); disabling needs no prompt. The prompt runs
     * through the same guarded path as [authenticateBiometrics] (`isAuthenticating`, "App authentication is unavailable
     * right now." when busy or backgrounded, late success discarded). Success sets `isEnabled`, `isLocked = false` and
     * persists. A failed prompt leaves it unchanged and unpersisted: the error is thrown (Settings shows it under the
     * switch) and, like iOS `performAuthentication`, also stored in [AppAccessState.error]; a dismissed prompt throws
     * `CancellationException`.
     */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Unlocks with biometrics; returns at once unless enabled and locked (title "Unlock BitTool Signer", description
     * "Signing still requires the wallet password.", negative "Use a Wallet Password" → `CancellationException`, the
     * lock screen then shows the password form). Refused with "App authentication is unavailable right now." while in
     * the background or while another authentication runs. A success that completes after the app went to the
     * background (or after a newer authentication started) is discarded and the app stays locked. A non-cancellation
     * failure is also published as [AppAccessState.error] (iOS `performAuthentication`).
     */
    suspend fun authenticateBiometrics()

    /**
     * Unlocks with any wallet's password (`WalletVault.verifyPassword`: counted and throttled like every unlock); the
     * secret dies inside the vault. Returns at once unless enabled and locked; same background/concurrency refusal and
     * late-result rule as [authenticateBiometrics]. Errors are vault errors ("The password is incorrect.", "Too many
     * unsuccessful attempts…"), also published as [AppAccessState.error].
     */
    suspend fun authenticate(walletId: WalletId, password: SecretText)

    /**
     * A one-off biometric check before deleting a wallet, only while App Lock is on and biometrics are available;
     * otherwise returns at once. `CancellationException` when dismissed (the caller aborts); any other failure means
     * biometrics could not run and the password alone decides (the caller continues).
     */
    suspend fun confirmSensitiveAction(reason: UiText)

    /**
     * Android-only (QA L2): whether no way of unlocking can succeed, proven afresh — removing the device's screen lock
     * deletes every wallet's device key and the enrolled fingerprints, and setting a new lock brings neither back.
     * True only while App Lock is on and locked, strong biometrics cannot run (`BiometricAuthenticator.isAvailable()`
     * now), and the vault lists at least one wallet and proves every one's device key gone (`WalletVault.isDeviceKeyLost`,
     * the multi-look Keystore proof; false without a secure lock screen). A wrong password, a damaged file, a flaky or
     * locked Keystore, an unreadable wallet list: false. No prompt, no counted attempt, nothing changes.
     *
     * The default is false.
     */
    suspend fun hasNoUnlockPath(): Boolean = false

    /**
     * Android-only (QA L2): "Continue" on the lock screen's explanation that no unlock path can succeed. Proves
     * [hasNoUnlockPath] again (never trusting an earlier answer) and only then turns App Lock off (persisted, like
     * [setEnabled] with `false`) and opens the app; returns false, changing nothing, when the proof fails. It authorises
     * nothing about a wallet: App Lock guards public pages only, and every wallet operation keeps its own checks (the
     * lost-key removal re-proves the loss in the vault). Same refusals as [authenticate] (background, another
     * authentication running: "App authentication is unavailable right now."); a failed preference write leaves App
     * Lock on and locked ("App Lock could not be changed. Try again."). True at once when App Lock is not locked.
     *
     * The default changes nothing and returns false.
     */
    suspend fun continueWithoutUnlockPath(): Boolean = false

    /** `onStop` without a configuration change: records the monotonic time and cancels any prompt. Idempotent. */
    fun didEnterBackground()

    /** `onStart`: applies the grace rule (locks when exceeded), refreshes biometric availability. Idempotent. */
    fun willEnterForeground()

    fun clearError()

    companion object {
        const val BACKGROUND_GRACE_MILLIS = 120_000L
        const val PREFERENCE_KEY = "appLockEnabled.v1"
    }
}

/** What a biometric prompt says; all texts are resolved in the app language. */
data class BiometricPromptSpec(
    val title: UiText,
    val description: UiText? = null,
    val negativeButton: UiText,
)

/** A biometric failure other than cancellation (app-shell.md §15.6 error mapping). */
class BiometricException(val kind: Kind, text: UiText) : SignerException(text) {
    enum class Kind {
        /** `ERROR_HW_UNAVAILABLE`, `ERROR_NO_BIOMETRICS`, `ERROR_HW_NOT_PRESENT`, `ERROR_SECURITY_UPDATE_REQUIRED`. */
        Unavailable,
        /** `ERROR_LOCKOUT`, `ERROR_LOCKOUT_PERMANENT`. */
        Lockout,
        /** Anything else: "Authentication failed. Try again or use a wallet password." */
        Failed,
    }
}

/**
 * `androidx.biometric.BiometricPrompt` behind an interface (so `AppAccessController` keeps the iOS epoch logic and the
 * tests a blocking fake). `BIOMETRIC_WEAK` (Class 2+), no `DEVICE_CREDENTIAL`, `setConfirmationRequired(false)`, no
 * `CryptoObject` (App Lock gates UI only). Shown only while the activity is resumed.
 */
interface BiometricAuthenticator {
    /** `canAuthenticate(BIOMETRIC_WEAK) == BIOMETRIC_SUCCESS`. */
    fun isAvailable(): Boolean

    /**
     * Shows [prompt] on `AppLifecycleMonitor.foregroundActivity` and returns on success. User cancel, negative button,
     * system cancel and timeout throw `CancellationException`; other errors throw [BiometricException]. A non-matching
     * attempt is not terminal. Without a resumed activity it throws `CancellationException` at once (callers start
     * prompts from a RESUMED effect: the lock screen's auto-prompt, a button tap). Cancelling the calling coroutine
     * cancels the prompt.
     */
    suspend fun authenticate(prompt: BiometricPromptSpec)

    /** Cancels a showing prompt (background, epoch change). */
    fun cancel()
}
