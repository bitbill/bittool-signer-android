package com.bittool.signer.core.security.privacy

import com.bittool.signer.core.security.PrivacySignal
import com.bittool.signer.core.security.PrivacySignalDispatcher
import com.bittool.signer.core.security.RecoveryPrivacySession.Prompt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Port of BitToolSignerTests/RecoveryPrivacyTests.swift (8 cases; the parameterised lifecycle case runs once per iOS
 * argument). "These tests exercise display permission only. No mnemonic, passphrase, image, wallet file, or real
 * screenshot is created or recorded by this suite."
 */
class RecoveryPrivacySessionTest {

    // RecoveryPrivacyTests.swift:11-23
    @Test
    fun startsHiddenAndATapRevealsWithoutConfirmation() {
        val session = DefaultRecoveryPrivacySession(PrivacySignalDispatcher(), canReveal = { true })
        assertFalse(session.isRevealed.value)
        assertNull(session.prompt.value)
        session.start()
        try {
            assertTrue(session.reveal())
            assertTrue(session.isRevealed.value)
            assertNull(session.prompt.value, "Revealing never raises a confirmation prompt.")
            session.hide()
            assertFalse(session.isRevealed.value)
            assertTrue(session.reveal())
        } finally {
            session.stop()
        }
    }

    // RecoveryPrivacyTests.swift:25-43
    @Test
    fun screenshotImmediatelyConcealsAndRequiresWarningAcknowledgement() {
        val center = PrivacySignalDispatcher()
        val session = DefaultRecoveryPrivacySession(center, canReveal = { true })
        session.start()
        try {
            assertTrue(session.reveal())
            center.post(PrivacySignal.Screenshot)
            assertFalse(session.isRevealed.value)
            assertEquals(Prompt.Screenshot, session.prompt.value)

            assertFalse(session.reveal())
            assertFalse(session.isRevealed.value)
            session.hide() // Hiding alone does not acknowledge the warning.
            assertFalse(session.reveal())
            session.acknowledgeWarning()
            assertNull(session.prompt.value)
            assertFalse(session.isRevealed.value, "Acknowledging never reveals by itself.")
            assertTrue(session.reveal())
        } finally {
            session.stop()
        }
    }

    // RecoveryPrivacyTests.swift:45-54
    @Test
    fun screenshotWhileHiddenStillWarnsWithoutExposingContent() {
        val center = PrivacySignalDispatcher()
        val session = DefaultRecoveryPrivacySession(center, canReveal = { true })
        session.start()
        try {
            center.post(PrivacySignal.Screenshot)
            assertFalse(session.isRevealed.value)
            assertEquals(Prompt.Screenshot, session.prompt.value)
            assertFalse(session.reveal())
        } finally {
            session.stop()
        }
    }

    // RecoveryPrivacyTests.swift:56-71, argument UIApplication.willResignActiveNotification (onPause)
    @Test
    fun lifecycleAndCaptureEventsConceal_willResignActive() = lifecycleAndCaptureEventsConceal(PrivacySignal.WillResignActive)

    // argument UIApplication.didEnterBackgroundNotification (onStop)
    @Test
    fun lifecycleAndCaptureEventsConceal_didEnterBackground() = lifecycleAndCaptureEventsConceal(PrivacySignal.DidEnterBackground)

    // argument UIScreen.capturedDidChangeNotification (screen-recording callback)
    @Test
    fun lifecycleAndCaptureEventsConceal_capturedDidChange() = lifecycleAndCaptureEventsConceal(PrivacySignal.CaptureChanged)

    private fun lifecycleAndCaptureEventsConceal(signal: PrivacySignal) {
        val center = PrivacySignalDispatcher()
        val session = DefaultRecoveryPrivacySession(center, canReveal = { true })
        session.start()
        try {
            assertTrue(session.reveal())
            center.post(signal)
            assertFalse(session.isRevealed.value)
            assertNull(session.prompt.value)
            assertTrue(session.reveal(), "Returning to a safe screen needs only a new tap.")
        } finally {
            session.stop()
        }
    }

    // RecoveryPrivacyTests.swift:73-82
    @Test
    fun unsafeEnvironmentRefusesToReveal() {
        var allowsReveal = false
        val session = DefaultRecoveryPrivacySession(PrivacySignalDispatcher(), canReveal = { allowsReveal })
        session.start()
        try {
            assertFalse(session.reveal()) // Not resumed, or a recorded/mirrored screen.
            assertFalse(session.isRevealed.value)
            allowsReveal = true
            assertTrue(session.reveal())
        } finally {
            session.stop()
        }
    }

    // RecoveryPrivacyTests.swift:84-100
    @Test
    fun aSessionPausedUnderASheetLeavesTheScreenshotWarningToTheSheet() {
        val center = PrivacySignalDispatcher()
        val covered = DefaultRecoveryPrivacySession(center, canReveal = { true })
        val sheet = DefaultRecoveryPrivacySession(center, canReveal = { true })
        covered.start()
        assertTrue(covered.reveal())
        covered.stop() // A sheet covers the panel.
        assertFalse(covered.isRevealed.value, "Pausing conceals the covered content.")
        sheet.start()
        center.post(PrivacySignal.Screenshot)
        assertEquals(Prompt.Screenshot, sheet.prompt.value)
        assertNull(covered.prompt.value, "Only the visible sheet raises the warning.")
        sheet.acknowledgeWarning()
        sheet.stop()
        covered.start() // The sheet closed.
        assertTrue(covered.reveal(), "One acknowledgement is enough to reveal again.")
        covered.stop()
    }

    // RecoveryPrivacyTests.swift:102-114
    @Test
    fun unmountedSessionsCannotReveal() {
        val center = PrivacySignalDispatcher()
        val session = DefaultRecoveryPrivacySession(center, canReveal = { true })
        assertFalse(session.reveal())
        assertFalse(session.isRevealed.value)
        session.start()
        assertTrue(session.reveal())
        session.stop()
        assertFalse(session.isRevealed.value)
        assertFalse(session.reveal())
        center.post(PrivacySignal.Screenshot)
        assertNull(session.prompt.value)
    }

    // RecoveryPrivacyTests.swift:116-140
    @Test
    fun remountResetsVisibilityAndDoesNotAccumulateObservers() = runTest {
        val center = PrivacySignalDispatcher()
        val session = DefaultRecoveryPrivacySession(center, canReveal = { true })
        var screenshotPrompts = 0
        val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.prompt.filter { it == Prompt.Screenshot }.collect { screenshotPrompts += 1 }
        }
        try {
            session.start()
            assertTrue(session.reveal())
            session.start() // Reappearance must reset a previous reveal.
            assertFalse(session.isRevealed.value)
            assertNull(session.prompt.value)
            assertEquals(1, center.observerCount)
            center.post(PrivacySignal.Screenshot)
            assertEquals(1, screenshotPrompts)

            session.stop()
            assertEquals(0, center.observerCount)
            center.post(PrivacySignal.Screenshot)
            assertEquals(1, screenshotPrompts)
            session.start()
            center.post(PrivacySignal.Screenshot)
            // Old observers would also fire after remount if stop merely set a flag.
            assertEquals(2, screenshotPrompts)
            assertEquals(1, center.observerCount)
        } finally {
            observation.cancel()
            session.stop()
        }
    }

    @Test
    fun foregroundSignalsNeitherRevealNorWarn() {
        val center = PrivacySignalDispatcher()
        val session = DefaultRecoveryPrivacySession(center, canReveal = { true })
        session.start()
        assertTrue(session.reveal())
        center.post(PrivacySignal.WillEnterForeground)
        center.post(PrivacySignal.DidBecomeActive)
        assertTrue(session.isRevealed.value)
        assertNull(session.prompt.value)
        session.stop()
    }
}
