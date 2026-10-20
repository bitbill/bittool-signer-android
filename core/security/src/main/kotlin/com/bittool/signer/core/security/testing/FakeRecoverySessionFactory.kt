package com.bittool.signer.core.security.testing

import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.core.security.RecoveryScreenshotSession
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.security.privacy.DefaultRecoverySessionFactory

/**
 * A [RecoverySessionFactory] for JVM tests of features: it hands out the **real** session state machines (iOS
 * `RecoveryPrivacySession` / `RecoveryScreenshotSession` semantics), driven by [monitor] instead of the activity, and
 * records every session it created. Drive concealment with `monitor.pause()`, `monitor.moveToBackground()`,
 * `monitor.setCaptured(true)` and `monitor.takeScreenshot()`. Not used by production code.
 */
class FakeRecoverySessionFactory(
    val monitor: FakeAppLifecycleMonitor = FakeAppLifecycleMonitor(),
) : RecoverySessionFactory {

    private val delegate = DefaultRecoverySessionFactory(monitor.privacySignals, monitor)

    /** Every privacy session created, in order. */
    val privacySessions: MutableList<RecoveryPrivacySession> = mutableListOf()

    /** Every screenshot session created, in order. */
    val screenshotSessions: MutableList<RecoveryScreenshotSession> = mutableListOf()

    override fun privacySession(): RecoveryPrivacySession = delegate.privacySession().also { privacySessions += it }

    override fun screenshotSession(): RecoveryScreenshotSession = delegate.screenshotSession().also { screenshotSessions += it }
}
