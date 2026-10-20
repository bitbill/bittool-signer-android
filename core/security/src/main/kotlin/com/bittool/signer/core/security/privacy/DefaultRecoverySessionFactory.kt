package com.bittool.signer.core.security.privacy

import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.PrivacySignalCenter
import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.core.security.RecoveryScreenshotSession
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.security.ScenePhase
import com.bittool.signer.core.security.lifecycle.DefaultAppLifecycleMonitor
import javax.inject.Inject

/**
 * Creates the per-display recovery sessions wired to the lifecycle monitor (app-shell.md §15.8): signals come from
 * [signals]; `canReveal()` = RESUMED and not captured (iOS `applicationState == .active && !UIScreen.main.isCaptured`,
 * RecoveryPrivacySession.swift:27); `isApplicationActive()` = RESUMED (RecoveryScreenshotSession.swift:22).
 */
internal class DefaultRecoverySessionFactory(
    private val signals: PrivacySignalCenter,
    private val monitor: AppLifecycleMonitor,
) : RecoverySessionFactory {

    @Inject constructor(monitor: DefaultAppLifecycleMonitor) : this(monitor.privacySignals, monitor)

    override fun privacySession(): RecoveryPrivacySession = DefaultRecoveryPrivacySession(
        center = signals,
        canReveal = { monitor.phase.value == ScenePhase.Active && !monitor.isCaptured.value },
    )

    override fun screenshotSession(): RecoveryScreenshotSession = DefaultRecoveryScreenshotSession(
        center = signals,
        isApplicationActive = { monitor.phase.value == ScenePhase.Active },
    )
}
