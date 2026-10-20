package com.bittool.signer.core.security.privacy

import com.bittool.signer.core.security.PrivacySignal
import com.bittool.signer.core.security.PrivacySignalDispatcher
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

/**
 * Port of BitToolSignerTests/RecoveryScreenshotTests.swift (5 cases; the parameterised lifecycle case runs once per
 * iOS argument). "Notification/lifecycle tests contain no phrase, image or screenshot data."
 */
class RecoveryScreenshotSessionTest {
    private class Environment {
        var active = true
        var callbacks = 0
    }

    // RecoveryScreenshotTests.swift:8-25
    @Test
    fun repeatedScreenshotsInvokeCallbackAndRequireCurrentAcknowledgement() {
        val center = PrivacySignalDispatcher()
        val environment = Environment()
        val session = DefaultRecoveryScreenshotSession(center, isApplicationActive = { environment.active })
        session.start { environment.callbacks += 1 }
        assertNull(session.warning.value)
        center.post(PrivacySignal.Screenshot)
        val first = assertNotNull(session.warning.value)
        center.post(PrivacySignal.Screenshot)
        val second = assertNotNull(session.warning.value)
        assertNotEquals(first.id, second.id)
        assertEquals(2, environment.callbacks)
        session.acknowledge(first)
        assertEquals(second, session.warning.value)
        session.acknowledge(second)
        assertNull(session.warning.value)
        session.stop()
    }

    // RecoveryScreenshotTests.swift:27-44, argument UIApplication.willResignActiveNotification (onPause)
    @Test
    fun lifecycleDismissesWarningAndDefersFurtherAlertsUntilActive_willResignActive() =
        lifecycleDismissesWarningAndDefersFurtherAlertsUntilActive(PrivacySignal.WillResignActive)

    // argument UIApplication.didEnterBackgroundNotification (onStop)
    @Test
    fun lifecycleDismissesWarningAndDefersFurtherAlertsUntilActive_didEnterBackground() =
        lifecycleDismissesWarningAndDefersFurtherAlertsUntilActive(PrivacySignal.DidEnterBackground)

    private fun lifecycleDismissesWarningAndDefersFurtherAlertsUntilActive(signal: PrivacySignal) {
        val center = PrivacySignalDispatcher()
        val environment = Environment()
        val session = DefaultRecoveryScreenshotSession(center, isApplicationActive = { environment.active })
        session.start { environment.callbacks += 1 }
        center.post(PrivacySignal.Screenshot)
        assertNotNull(session.warning.value)
        center.post(signal)
        assertNull(session.warning.value)
        center.post(PrivacySignal.Screenshot)
        assertNull(session.warning.value)
        assertEquals(2, environment.callbacks)
        center.post(PrivacySignal.DidBecomeActive)
        center.post(PrivacySignal.Screenshot)
        assertNotNull(session.warning.value)
        session.stop()
    }

    // RecoveryScreenshotTests.swift:46-61
    @Test
    fun currentApplicationStateAlsoPreventsBackgroundPresentation() {
        val center = PrivacySignalDispatcher()
        val environment = Environment().apply { active = false }
        val session = DefaultRecoveryScreenshotSession(center, isApplicationActive = { environment.active })
        session.start { environment.callbacks += 1 }
        center.post(PrivacySignal.Screenshot)
        assertNull(session.warning.value)
        environment.active = true
        center.post(PrivacySignal.DidBecomeActive)
        environment.active = false
        center.post(PrivacySignal.Screenshot)
        assertNull(session.warning.value)
        assertEquals(2, environment.callbacks)
        session.stop()
    }

    // RecoveryScreenshotTests.swift:63-82
    @Test
    fun stoppingAndRemountingDoesNotRetainWarningsCallbacksOrObservers() {
        val center = PrivacySignalDispatcher()
        val environment = Environment()
        val session = DefaultRecoveryScreenshotSession(center, isApplicationActive = { environment.active })
        session.start { environment.callbacks += 1 }
        center.post(PrivacySignal.Screenshot)
        assertEquals(1, environment.callbacks)
        session.stop()
        assertNull(session.warning.value)
        center.post(PrivacySignal.Screenshot)
        assertEquals(1, environment.callbacks)
        assertNull(session.warning.value)
        session.start { environment.callbacks += 10 }
        session.start { environment.callbacks += 100 }
        assertNull(session.warning.value)
        assertEquals(1, center.observerCount)
        center.post(PrivacySignal.Screenshot)
        assertEquals(101, environment.callbacks)
        assertNotNull(session.warning.value)
        session.stop()
        assertEquals(0, center.observerCount)
    }

    // RecoveryScreenshotTests.swift:84-92
    @Test
    fun unmountingInsideScreenshotCallbackDoesNotPublishLateWarning() {
        val center = PrivacySignalDispatcher()
        lateinit var session: DefaultRecoveryScreenshotSession
        session = DefaultRecoveryScreenshotSession(center, isApplicationActive = { true })
        session.start { session.stop() }
        center.post(PrivacySignal.Screenshot)
        assertNull(session.warning.value)
        center.post(PrivacySignal.Screenshot)
        assertNull(session.warning.value)
    }

    @Test
    fun aCaptureChangeNeitherWarnsNorDismisses() {
        val center = PrivacySignalDispatcher()
        val session = DefaultRecoveryScreenshotSession(center, isApplicationActive = { true })
        session.start {}
        center.post(PrivacySignal.Screenshot)
        val warning = assertNotNull(session.warning.value)
        center.post(PrivacySignal.CaptureChanged)
        assertEquals(warning, session.warning.value, "Content visibility is not this session's concern")
        session.stop()
    }
}
