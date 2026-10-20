package com.bittool.signer.core.security.access

import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.common.coroutines.MainDispatcher
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.common.time.MonotonicClock
import com.bittool.signer.core.crypto.vault.WalletVault
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppAccessController.Companion.BACKGROUND_GRACE_MILLIS
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.BiometricAuthenticator
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Port of iOS `AppAccessController` (Services/AppAccessController.swift:81-213; app-shell.md §11). "This controller
 * authenticates access to public app pages only. It never derives accounts, returns a secret, chooses a signer, or
 * authorizes signing." (:81-82)
 *
 * Every field is confined to the main thread like the iOS `@MainActor` class: the suspend functions switch to
 * [mainDispatcher] (`Dispatchers.Main.immediate`, so a caller on the main thread runs without a dispatch) and the
 * lifecycle functions are called from activity callbacks.
 *
 * Android differences, all fail-closed:
 * - The `appLockEnabled.v1` preference is read asynchronously (DataStore). Until it is read the published state is
 *   `resolving` and locked (app-shell.md §15.2, Ownbit `LockTiming.status`), and the suspend functions wait for the
 *   read before applying the iOS guards, which iOS evaluates against the value read synchronously in `init` (:113).
 *   An unreadable preference counts as "on": any wallet's password still opens the app, while a silently disabled
 *   lock would not be noticed.
 * - A wallet count of 0 reported before the read completes wins over a stale `true` (no lock that no password opens).
 * - The preference write of [setEnabled] is awaited before the state changes, so a failed write leaves App Lock as it
 *   was (iOS `UserDefaults` cannot fail); every write goes through one FIFO [Mutex], in call order.
 * - Time is [MonotonicClock] = `SystemClock.elapsedRealtime()`, which counts deep sleep like iOS
 *   `CLOCK_MONOTONIC_RAW` (:101-105; review fix F8): "`systemUptime` pauses during sleep, which would let a phone
 *   asleep for hours reopen unlocked."
 */
@Singleton
internal class DefaultAppAccessController @Inject constructor(
    private val preferences: SignerPreferences,
    private val vault: WalletVault,
    private val biometric: BiometricAuthenticator,
    private val clock: MonotonicClock,
    @ApplicationScope private val scope: CoroutineScope,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : AppAccessController {

    private var resolved = false
    private var isEnabled = false

    /** Fail closed: locked until the preference says otherwise (iOS `isLocked = enabled`, :116). */
    private var isLocked = true
    private var isAuthenticating = false
    private var biometricsAvailable = biometric.isAvailable()
    private var error: UiText? = null

    /** Null until the repository reported a finished load (iOS `walletCount: Int?`, :94). */
    private var walletCount: Int? = null
    private var backgroundTime: Long? = null
    private var isInBackground = false

    /** iOS `authenticationEpoch = UUID()` (:97): every new authentication and every cancellation renews it. */
    private var authenticationEpoch = 0L

    private val resolution = CompletableDeferred<Unit>()
    private val preferenceWrites = Mutex()

    private val _state = MutableStateFlow(snapshot())
    override val state: StateFlow<AppAccessState> = _state.asStateFlow()

    init {
        scope.launch(mainDispatcher) { resolve() }
    }

    /** iOS `init` (:107-117): "No authenticated state is persisted: enabled cold launches start locked." */
    private suspend fun resolve() {
        val stored = try {
            preferences.load().appLockEnabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "App Lock preference unreadable; treating App Lock as on" }
            true
        }
        resolved = true
        isEnabled = stored && walletCount != 0
        isLocked = isEnabled
        publish()
        resolution.complete(Unit)
    }

    /** iOS `updateWalletCount(_:)` (:119-127): "Call after the repository has finished loading, not for a loading placeholder." */
    override fun updateWalletCount(count: Int) {
        val known = count.coerceAtLeast(0)
        walletCount = known
        if (known == 0) {
            // Removing the last wallet disables App Lock: no wallet password could open it any more.
            cancelAuthentication()
            isEnabled = false
            isLocked = false
            backgroundTime = null
            persistLater(false)
        }
        publish()
    }

    /** iOS `setEnabled(_:)` (:129-140). Disabling needs no biometric check, only the unlocked foreground state. */
    override suspend fun setEnabled(enabled: Boolean): Unit = onMain {
        awaitResolution()
        if (enabled == isEnabled) return@onMain
        if (isLocked || isInBackground) throw AppIssue("Authenticate app access before changing App Lock.")
        if (enabled) {
            val count = walletCount
            if (count == null || count <= 0) throw AppIssue("Create a wallet before enabling App Lock.")
            val epoch = performAuthentication { biometric.authenticate(AppLockPrompts.enable) }
            requireAuthentication(epoch)
            requireWalletForLock()
        }
        try {
            persist(enabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "App Lock preference could not be saved" }
            throw SignerException(UiText.Resource(R.string.security_app_lock_save_failed), e)
        }
        // The last wallet may have been removed during the write; its own write of `false` is queued after ours.
        if (enabled) requireWalletForLock()
        isEnabled = enabled
        isLocked = false
        publish()
    }

    /** iOS `authenticateBiometrics()` (:142-147). */
    override suspend fun authenticateBiometrics(): Unit = onMain {
        awaitResolution()
        if (!isEnabled || !isLocked) return@onMain
        val epoch = performAuthentication { biometric.authenticate(AppLockPrompts.unlock) }
        requireAuthentication(epoch)
        isLocked = false
        publish()
    }

    /**
     * iOS `confirmSensitiveAction(reason:)` (:149-160): "it runs only while App Lock is on. Turning App Lock off is a
     * decision that this app does not use biometrics, and a delete must not reintroduce a Face ID prompt that the
     * person declined everywhere else. The wallet password still authorizes the action either way."
     */
    override suspend fun confirmSensitiveAction(reason: UiText): Unit = onMain {
        awaitResolution()
        if (!isEnabled || !biometricsAvailable) return@onMain
        biometric.authenticate(AppLockPrompts.confirm(reason))
    }

    /**
     * iOS `authenticate(walletID:password:)` (:162-167): any wallet's password opens the app. The vault counts the
     * attempt before decrypting and imposes its per-wallet retry delay (`min(300, 2^(count−4))` s from the 5th
     * consecutive failure, WalletVault.swift:135-152); its errors are shown verbatim. The caller owns [password].
     */
    override suspend fun authenticate(walletId: WalletId, password: SecretText): Unit = onMain {
        awaitResolution()
        if (!isEnabled || !isLocked) return@onMain
        val epoch = performAuthentication { vault.verifyPassword(walletId, password) }
        requireAuthentication(epoch)
        isLocked = false
        publish()
    }

    /**
     * iOS `didEnterBackground()` (:169-175): "A true background transition counts; Face ID's temporary inactive phase
     * does not call this method and cannot cause an authentication loop."
     */
    override fun didEnterBackground() {
        if (isInBackground) return
        isInBackground = true
        backgroundTime = clock.elapsedRealtimeMillis()
        cancelAuthentication()
        publish()
    }

    /**
     * iOS `willEnterForeground()` (:177-189), idempotent (called on `onStart`, and again harmlessly). Stays unlocked only
     * when `since != null && now >= since && now - since < 120 s`; a clock that went backwards locks. "Preserve an
     * already locked state even during a short background visit": this never unlocks.
     */
    override fun willEnterForeground() {
        if (!isInBackground) return
        isInBackground = false
        biometricsAvailable = biometric.isAvailable()
        val since = backgroundTime
        backgroundTime = null
        if (isEnabled) {
            val now = clock.elapsedRealtimeMillis()
            val withinGrace = since != null && now >= since && now - since < BACKGROUND_GRACE_MILLIS
            if (!withinGrace) isLocked = true
        }
        publish()
    }

    override fun clearError() {
        if (error == null) return
        error = null
        publish()
    }

    /** iOS `cancelAuthentication()` (:191-193). Callers publish. */
    private fun cancelAuthentication() {
        authenticationEpoch++
        biometric.cancel()
        isAuthenticating = false
    }

    /**
     * iOS `requireAuthentication(_:)` (:195-198): the result of an authentication counts only while its coroutine is
     * active, it is still the current one and the app is in the foreground; otherwise it is a silent cancellation.
     * Effect: a biometric success that completes after the app went to the background is discarded.
     */
    private suspend fun requireAuthentication(epoch: Long) {
        currentCoroutineContext().ensureActive()
        if (epoch != authenticationEpoch || isInBackground) throw CancellationException("App authentication was superseded")
    }

    /** iOS `setEnabled` re-check after the prompt (:136): enabling with no wallet left would lock the app for good. */
    private fun requireWalletForLock() {
        if ((walletCount ?: 0) <= 0) throw CancellationException("No wallet left to open App Lock")
    }

    /**
     * iOS `performAuthentication(_:)` (:200-212): refuses while in the background or while another authentication
     * runs; a non-cancellation failure of the current attempt is also published for the lock screen.
     */
    private suspend inline fun performAuthentication(operation: () -> Unit): Long {
        if (isInBackground || isAuthenticating) throw AppIssue("App authentication is unavailable right now.")
        val epoch = ++authenticationEpoch
        isAuthenticating = true
        error = null
        publish()
        try {
            operation()
            requireAuthentication(epoch)
            return epoch
        } catch (e: Throwable) {
            if (epoch == authenticationEpoch && e !is CancellationException) error = e.toUiText()
            throw e
        } finally {
            if (epoch == authenticationEpoch) isAuthenticating = false
            publish()
        }
    }

    private suspend fun awaitResolution() {
        if (!resolved) resolution.await()
    }

    private suspend fun persist(enabled: Boolean) {
        preferenceWrites.withLock { preferences.setAppLockEnabled(enabled) }
    }

    /**
     * Fire-and-forget write for the non-suspending [updateWalletCount] (iOS writes `UserDefaults` synchronously).
     * Started undispatched, so it takes its place in the FIFO [preferenceWrites] queue before this call returns.
     */
    private fun persistLater(enabled: Boolean) {
        scope.launch(mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                persist(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next launch reports the same wallet count and writes again; the lock cannot deadlock meanwhile.
                Log.w(TAG, e) { "App Lock preference could not be saved" }
            }
        }
    }

    private suspend inline fun <T> onMain(crossinline block: suspend () -> T): T = withContext(mainDispatcher) { block() }

    private fun snapshot() = AppAccessState(
        resolving = !resolved,
        isEnabled = resolved && isEnabled,
        isLocked = !resolved || isLocked,
        isAuthenticating = isAuthenticating,
        biometricsAvailable = biometricsAvailable,
        error = error,
    )

    private fun publish() {
        _state.value = snapshot()
    }

    private companion object {
        const val TAG = "AppAccess"
    }
}
