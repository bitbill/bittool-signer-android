package com.bittool.signer.core.security.window

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [DefaultWindowGate]: closed at once, reopened only once no lock window (or its fade-out) is on screen. */
class WindowGateTest {
    private class Presence : LockWindowPresence {
        private val listeners = mutableListOf<() -> Unit>()
        override var isPresent: Boolean = false
            set(value) {
                field = value
                listeners.forEach { it() }
            }

        override fun addPresenceListener(listener: () -> Unit) {
            listeners += listener
        }
    }

    @Test
    fun theGateStartsClosedAndOpensAtOnceWithoutALockWindow() {
        val gate = DefaultWindowGate(LockWindowPresence.None)
        assertFalse(gate.isOpen.value, "Fail closed until :app opens it")
        gate.setOpen(true)
        assertTrue(gate.isOpen.value)
        gate.setOpen(false)
        assertFalse(gate.isOpen.value, "Closing takes effect in the same frame")
    }

    @Test
    fun windowsWaitUntilTheLockWindowHasFadedOutAndGone() {
        val presence = Presence()
        val gate = DefaultWindowGate(presence)
        presence.isPresent = true
        gate.setOpen(false)
        // Unlocked: :app asks to open while the lock screen is still fading out.
        gate.setOpen(true)
        assertFalse(gate.isOpen.value)
        presence.isPresent = false
        assertTrue(gate.isOpen.value)
        // A lock window shown again closes the gate even before :app reacts.
        presence.isPresent = true
        assertFalse(gate.isOpen.value)
    }

    @Test
    fun awaitOpenSuspendsUntilReleased() = runTest {
        val gate = DefaultWindowGate(LockWindowPresence.None)
        val waiting = async { gate.awaitOpen() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        gate.setOpen(true)
        runCurrent()
        assertTrue(waiting.isCompleted)
    }
}
