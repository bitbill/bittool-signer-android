package com.bittool.signer.core.security.privacy

import com.bittool.signer.core.security.PrivacySignal
import com.bittool.signer.core.security.PrivacySignalCenter
import com.bittool.signer.core.security.RecoveryScreenshotSession
import com.bittool.signer.core.security.RecoveryScreenshotSession.Warning
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Port of iOS `RecoveryScreenshotSession` (Services/RecoveryScreenshotSession.swift:4-83; app-shell.md §13.2):
 * "Screenshot notification and warning state only. This component does not own recovery material or control whether
 * the underlying content is visible." Used on directly visible recovery material (the import phrase editor, the backup
 * check).
 *
 * [isApplicationActive] is injected like the iOS parameter (:20-23); in the app it is "RESUMED". Main thread only.
 */
internal class DefaultRecoveryScreenshotSession(
    private val center: PrivacySignalCenter,
    private val isApplicationActive: () -> Boolean,
) : RecoveryScreenshotSession {

    private val _warning = MutableStateFlow<Warning?>(null)
    override val warning: StateFlow<Warning?> = _warning.asStateFlow()

    private var observation: AutoCloseable? = null
    private var mounted = false
    private var active = false
    private var onScreenshot: (() -> Unit)? = null

    /** iOS `start(onScreenshot:)` (:28-39). */
    override fun start(onScreenshot: () -> Unit) {
        stop()
        mounted = true
        active = isApplicationActive()
        this.onScreenshot = onScreenshot
        observation = center.observe { signal ->
            if (!mounted) return@observe
            when (signal) {
                PrivacySignal.Screenshot -> receiveScreenshot()
                PrivacySignal.WillResignActive,
                PrivacySignal.DidEnterBackground,
                -> deactivate()
                PrivacySignal.DidBecomeActive -> active = isApplicationActive()
                PrivacySignal.WillEnterForeground,
                PrivacySignal.CaptureChanged,
                -> Unit
            }
        }
    }

    /** iOS `stop()` (:41-48). */
    override fun stop() {
        mounted = false
        active = false
        observation?.close()
        observation = null
        onScreenshot = null
        dismissWarning()
    }

    /** iOS `acknowledge(_:)` (:50-54): "A button from a replaced alert must not acknowledge a later screenshot." */
    override fun acknowledge(warning: Warning) {
        if (_warning.value?.id != warning.id) return
        dismissWarning()
    }

    /**
     * iOS `receiveScreenshot()` (:56-62): the callback always runs first. "The callback may itself unmount the view.
     * Never show a late warning after that, or re-present one while the app is inactive/backgrounded."
     */
    private fun receiveScreenshot() {
        onScreenshot?.invoke()
        if (!mounted || !active || !isApplicationActive()) return
        _warning.value = Warning(nextWarningId.incrementAndGet())
    }

    private fun deactivate() {
        active = false
        dismissWarning()
    }

    private fun dismissWarning() {
        _warning.value = null
    }

    private companion object {
        /** iOS gives every warning a fresh `UUID`; a process-wide counter is unique in the same way. */
        val nextWarningId = AtomicLong()
    }
}
