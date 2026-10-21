package com.bittool.signer.core.security.lifecycle

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.fragment.app.FragmentActivity
import com.bittool.signer.core.security.LifecycleEvent
import com.bittool.signer.core.security.PrivacySignal
import com.bittool.signer.core.security.ScenePhase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * [DefaultAppLifecycleMonitor] driven by a real activity lifecycle (app-shell.md §15.4): the iOS phase edges, the
 * configuration-change exception, the resumed activity for `BiometricPrompt`, synchronous privacy signals that
 * observe the new phase, and "captured" = recorded or mirrored to another display (iOS `UIScreen.isCaptured`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class AppLifecycleMonitorTest {

    /** A host whose `isChangingConfigurations` the test controls (Robolectric does not set it on recreate). */
    class HostActivity : FragmentActivity() {
        var changing = false
        override fun isChangingConfigurations(): Boolean = changing
    }

    private fun attached(monitor: DefaultAppLifecycleMonitor): ActivityController<HostActivity> {
        val controller = Robolectric.buildActivity(HostActivity::class.java)
        monitor.attach(controller.get())
        return controller
    }

    @Test
    fun activityCallbacksMapToTheIosScenePhases() = runTest {
        val monitor = DefaultAppLifecycleMonitor()
        val events = mutableListOf<LifecycleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.events.collect { events += it } }
        val controller = attached(monitor)
        assertEquals(ScenePhase.Background, monitor.phase.value)

        controller.create().start()
        assertEquals(ScenePhase.Inactive, monitor.phase.value)
        controller.resume()
        assertEquals(ScenePhase.Active, monitor.phase.value)
        assertSame(controller.get(), monitor.foregroundActivity)
        controller.pause()
        assertEquals(ScenePhase.Inactive, monitor.phase.value)
        assertNull(monitor.foregroundActivity, "Prompts are shown only on a resumed activity")
        controller.stop()
        assertEquals(ScenePhase.Background, monitor.phase.value)
        controller.start().resume()
        assertEquals(
            listOf(
                LifecycleEvent.WillEnterForeground,
                LifecycleEvent.DidBecomeActive,
                LifecycleEvent.WillResignActive,
                LifecycleEvent.DidEnterBackground,
                LifecycleEvent.WillEnterForeground,
                LifecycleEvent.DidBecomeActive,
            ),
            events,
        )
    }

    @Test
    fun aConfigurationChangeIsNotABackgroundVisit() = runTest {
        val monitor = DefaultAppLifecycleMonitor()
        val events = mutableListOf<LifecycleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.events.collect { events += it } }
        val old = attached(monitor)
        old.create().start().resume()
        events.clear()

        // Per-app language change: the activity is recreated.
        old.get().changing = true
        old.pause().stop().destroy()
        assertEquals(ScenePhase.Inactive, monitor.phase.value)
        val recreated = attached(monitor)
        recreated.create().start().resume()

        assertEquals(listOf(LifecycleEvent.WillResignActive, LifecycleEvent.DidBecomeActive), events)
        assertEquals(ScenePhase.Active, monitor.phase.value)
        assertSame(recreated.get(), monitor.foregroundActivity)
    }

    @Test
    fun privacySignalsAreSynchronousAndSeeTheNewPhase() {
        val monitor = DefaultAppLifecycleMonitor()
        val seen = mutableListOf<Pair<PrivacySignal, ScenePhase>>()
        monitor.privacySignals.observe { seen += it to monitor.phase.value }
        val controller = attached(monitor)
        controller.create().start().resume()
        controller.pause()
        assertEquals(PrivacySignal.WillResignActive to ScenePhase.Inactive, seen.last(), "Delivered inside onPause")
        controller.stop()
        assertEquals(
            listOf(
                PrivacySignal.WillEnterForeground to ScenePhase.Inactive,
                PrivacySignal.DidBecomeActive to ScenePhase.Active,
                PrivacySignal.WillResignActive to ScenePhase.Inactive,
                PrivacySignal.DidEnterBackground to ScenePhase.Background,
            ),
            seen,
        )
    }

    @Test
    fun captureAndScreenshotCallbacksReachFlowsAndSignals() = runTest {
        val monitor = DefaultAppLifecycleMonitor()
        val signals = mutableListOf<PrivacySignal>()
        monitor.privacySignals.observe { signals += it }
        var screenshots = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.screenshots.collect { screenshots += 1 } }

        monitor.onRecordingChanged(true)
        assertTrue(monitor.isCaptured.value)
        monitor.onRecordingChanged(true) // no change, no signal
        monitor.onRecordingChanged(false)
        assertFalse(monitor.isCaptured.value)
        monitor.onScreenshot()

        assertEquals(listOf(PrivacySignal.CaptureChanged, PrivacySignal.CaptureChanged, PrivacySignal.Screenshot), signals)
        assertEquals(1, screenshots)
    }

    @Test
    fun recordingOrMirroringIsCapturedAndEachChangeOfTheCombinationIsSignalled() {
        // iOS `UIScreen.isCaptured`: recorded, mirrored, AirPlayed or otherwise cloned (A03).
        val monitor = DefaultAppLifecycleMonitor()
        val signals = mutableListOf<PrivacySignal>()
        monitor.privacySignals.observe { signals += it }

        monitor.onMirroringChanged(true)
        assertTrue(monitor.isCaptured.value)
        monitor.onRecordingChanged(true) // still captured: no new signal
        monitor.onMirroringChanged(false)
        assertTrue(monitor.isCaptured.value, "Still recorded")
        monitor.onRecordingChanged(false)
        assertFalse(monitor.isCaptured.value)

        assertEquals(listOf(PrivacySignal.CaptureChanged, PrivacySignal.CaptureChanged), signals)
    }

    @Test
    fun mirroringToAnotherDisplayIsCapturedUntilItIsDisconnected() {
        val monitor = DefaultAppLifecycleMonitor()
        val signals = mutableListOf<PrivacySignal>()
        monitor.privacySignals.observe { signals += it }
        val controller = attached(monitor)
        controller.create().start().resume()
        assertFalse(monitor.isCaptured.value, "The built-in display alone is not a mirror")
        signals.clear()

        // A USB-C to HDMI cable, or Miracast: a presentation display that mirrors the phone.
        val tv = connectDisplay(Display.FLAG_PRESENTATION)
        assertTrue(monitor.isCaptured.value)
        assertEquals(listOf(PrivacySignal.CaptureChanged), signals)

        // Switched off, then on again, then unplugged.
        setDisplayState(tv, Display.STATE_OFF)
        assertFalse(monitor.isCaptured.value)
        setDisplayState(tv, Display.STATE_ON)
        assertTrue(monitor.isCaptured.value)
        ShadowDisplayManager.removeDisplay(tv)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(monitor.isCaptured.value)
        assertEquals(List(4) { PrivacySignal.CaptureChanged }, signals)
    }

    @Test
    fun aDisplayConnectedInTheBackgroundIsCapturedOnReturn() {
        val monitor = DefaultAppLifecycleMonitor()
        val controller = attached(monitor)
        controller.create().start().resume().pause().stop()
        connectDisplay(Display.FLAG_PRESENTATION)
        assertFalse(monitor.isCaptured.value, "No listener while stopped")
        controller.start()
        assertTrue(monitor.isCaptured.value, "Applied at once in onStart, before the activity resumes")
    }

    @Test
    fun displaysThatCannotMirrorTheAppAreNotCaptured() {
        val monitor = DefaultAppLifecycleMonitor()
        val controller = attached(monitor)
        controller.create().start().resume()
        // A secondary display that is not a presentation display (e.g. an OEM's internal panel).
        connectDisplay(0)
        // A private display: only the app that created it can see it, so it is this app's own virtual display.
        connectDisplay(Display.FLAG_PRESENTATION or Display.FLAG_PRIVATE)
        assertFalse(monitor.isCaptured.value)
    }

    @Test
    fun theMirrorRuleExcludesTheBuiltInTheActivitysOwnPrivateAndSwitchedOffDisplays() {
        val presentation = Display.FLAG_PRESENTATION
        val on = Display.STATE_ON
        val own = Display.DEFAULT_DISPLAY
        assertTrue(DisplayMirroring.isMirrorTarget(displayId = 2, flags = presentation, state = on, ownDisplayId = own))
        assertTrue(
            DisplayMirroring.isMirrorTarget(2, presentation or Display.FLAG_SECURE, Display.STATE_UNKNOWN, own),
            "An HDCP sink is still a mirror (FLAG_SECURE content is shown on it)",
        )
        assertFalse(DisplayMirroring.isMirrorTarget(Display.DEFAULT_DISPLAY, presentation, on, own), "Built-in")
        assertFalse(DisplayMirroring.isMirrorTarget(2, presentation, on, ownDisplayId = 2), "The app runs there")
        assertFalse(DisplayMirroring.isMirrorTarget(2, presentation or Display.FLAG_PRIVATE, on, own), "Own virtual")
        assertFalse(DisplayMirroring.isMirrorTarget(2, presentation, Display.STATE_OFF, own), "Switched off")
        assertFalse(DisplayMirroring.isMirrorTarget(2, 0, on, own), "Not a presentation display")
    }

    @Test
    fun aReplacedActivityNoLongerDrivesTheMonitor() {
        val monitor = DefaultAppLifecycleMonitor()
        val first = attached(monitor)
        first.create().start().resume()
        val second = attached(monitor)
        second.create().start().resume()
        first.pause().stop()
        assertEquals(ScenePhase.Active, monitor.phase.value)
        assertSame(second.get(), monitor.foregroundActivity)
    }

    /** Adds a display with [flags] and delivers the listener callbacks. */
    private fun connectDisplay(flags: Int): Int {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        if (flags != 0) shadowOf(displayManager.getDisplay(id)).setFlags(flags)
        shadowOf(Looper.getMainLooper()).idle()
        return id
    }

    private fun setDisplayState(id: Int, state: Int) {
        shadowOf(displayManager.getDisplay(id)).setState(state)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val displayManager: DisplayManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(DisplayManager::class.java)

    @Test
    fun aDestroyedActivityIsReleased() {
        val monitor = DefaultAppLifecycleMonitor()
        val controller = attached(monitor)
        controller.create().start().resume().pause().stop().destroy()
        assertNull(monitor.foregroundActivity)
        assertEquals(ScenePhase.Background, monitor.phase.value)
    }
}
