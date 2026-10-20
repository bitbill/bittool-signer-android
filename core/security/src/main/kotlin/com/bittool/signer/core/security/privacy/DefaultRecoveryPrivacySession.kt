package com.bittool.signer.core.security.privacy

import com.bittool.signer.core.security.PrivacySignal
import com.bittool.signer.core.security.PrivacySignalCenter
import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.core.security.RecoveryPrivacySession.Prompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Port of iOS `RecoveryPrivacySession` (Services/RecoveryPrivacySession.swift:4-83; app-shell.md §13.1). "Display
 * permission only: this object never owns a mnemonic or passphrase."
 *
 * "Tapping "Tap to Reveal" is the consent: the content shows at once, without a second confirmation. It is concealed
 * again on any screenshot, when the app resigns active or enters the background, and while the screen is captured; a
 * screenshot also raises a warning that must be acknowledged before the content can be shown again." (:6-10)
 *
 * [center] and [canReveal] are injected like the iOS `center:` / `canReveal:` parameters (:25-28); in the app
 * [canReveal] is "RESUMED and not captured" (app-shell.md §15.8). Signals arriving while unmounted are ignored, and a
 * re-mount replaces the previous observer instead of adding one. Main thread only.
 */
internal class DefaultRecoveryPrivacySession(
    private val center: PrivacySignalCenter,
    private val canReveal: () -> Boolean,
) : RecoveryPrivacySession {

    private val _isRevealed = MutableStateFlow(false)
    override val isRevealed: StateFlow<Boolean> = _isRevealed.asStateFlow()

    private val _prompt = MutableStateFlow<Prompt?>(null)
    override val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    private var observation: AutoCloseable? = null
    private var mounted = false

    /** iOS `start()` (:33-43): a re-mount resets any reveal and the observers. */
    override fun start() {
        stop()
        mounted = true
        observation = center.observe { signal ->
            if (!mounted) return@observe
            when (signal) {
                PrivacySignal.Screenshot -> {
                    hide()
                    _prompt.value = Prompt.Screenshot
                }
                PrivacySignal.WillResignActive,
                PrivacySignal.DidEnterBackground,
                PrivacySignal.CaptureChanged,
                -> hide()
                PrivacySignal.WillEnterForeground,
                PrivacySignal.DidBecomeActive,
                -> Unit
            }
        }
    }

    /** iOS `stop()` (:45-51). */
    override fun stop() {
        mounted = false
        observation?.close()
        observation = null
        hide()
        _prompt.value = null
    }

    /**
     * iOS `reveal()` (:53-61): "Shows the content if the screen is safe to show it on: the view is mounted, the app is
     * active, the screen is not being captured, and no screenshot warning is waiting to be acknowledged."
     */
    override fun reveal(): Boolean {
        if (!mounted || !canReveal() || _prompt.value != null) return false
        _isRevealed.value = true
        return true
    }

    override fun hide() {
        _isRevealed.value = false
    }

    /** iOS `acknowledgeWarning()` (:67-71), the warning's OK button: never reveals by itself. */
    override fun acknowledgeWarning() {
        hide()
        _prompt.value = null
    }
}
