package com.bittool.signer.core.security.privacy

import com.bittool.signer.core.security.RecoveryPrivacySession.Prompt
import com.bittool.signer.core.security.ScenePhase
import com.bittool.signer.core.security.testing.FakeAppLifecycleMonitor
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The factory's wiring of the iOS defaults (app-shell.md §15.8): `canReveal()` = RESUMED and not captured
 * (RecoveryPrivacySession.swift:27), `isApplicationActive()` = RESUMED (RecoveryScreenshotSession.swift:22), and the
 * monitor's lifecycle, capture and screenshot signals reach the sessions synchronously.
 */
class RecoverySessionFactoryTest {

    @Test
    fun revealNeedsAResumedUncapturedScreen() {
        val monitor = FakeAppLifecycleMonitor(initialPhase = ScenePhase.Inactive)
        val session = FakeRecoverySessionFactory(monitor).privacySession()
        session.start()
        assertFalse(session.reveal(), "Not resumed")
        monitor.resume()
        monitor.setCaptured(true)
        assertFalse(session.reveal(), "Recorded or mirrored")
        monitor.setCaptured(false)
        assertTrue(session.reveal())
        session.stop()
    }

    @Test
    fun pauseBackgroundAndCaptureConcealAtOnce() {
        val monitor = FakeAppLifecycleMonitor()
        val session = FakeRecoverySessionFactory(monitor).privacySession()
        session.start()
        assertTrue(session.reveal())
        monitor.pause()
        assertFalse(session.isRevealed.value, "Concealed before onPause returns")
        monitor.resume()
        assertTrue(session.reveal())
        monitor.moveToBackground()
        assertFalse(session.isRevealed.value)
        monitor.returnToForeground()
        assertTrue(session.reveal())
        monitor.setCaptured(true)
        assertFalse(session.isRevealed.value)
        assertNull(session.prompt.value)
        session.stop()
    }

    @Test
    fun aScreenshotConcealsAndWarnsOnBothSessionKinds() {
        val factory = FakeRecoverySessionFactory()
        val privacy = factory.privacySession()
        val screenshots = factory.screenshotSession()
        var callbacks = 0
        privacy.start()
        screenshots.start { callbacks += 1 }
        assertTrue(privacy.reveal())
        factory.monitor.takeScreenshot()
        assertFalse(privacy.isRevealed.value)
        assertEquals(Prompt.Screenshot, privacy.prompt.value)
        assertNotNull(screenshots.warning.value)
        assertEquals(1, callbacks)
        assertEquals(listOf(privacy), factory.privacySessions)
        assertEquals(listOf(screenshots), factory.screenshotSessions)
        privacy.stop()
        screenshots.stop()
        assertEquals(0, factory.monitor.privacyObserverCount)
    }

    @Test
    fun aScreenshotWhileInactiveCallsBackWithoutAWarning() {
        val monitor = FakeAppLifecycleMonitor()
        val session = FakeRecoverySessionFactory(monitor).screenshotSession()
        var callbacks = 0
        session.start { callbacks += 1 }
        monitor.pause()
        monitor.takeScreenshot()
        assertNull(session.warning.value)
        monitor.resume()
        monitor.takeScreenshot()
        assertNotNull(session.warning.value)
        assertEquals(2, callbacks)
        session.stop()
    }
}
