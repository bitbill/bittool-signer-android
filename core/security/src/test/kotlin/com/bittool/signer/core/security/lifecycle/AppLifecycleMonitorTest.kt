package com.bittool.signer.core.security.lifecycle

import android.os.Build
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * [DefaultAppLifecycleMonitor] driven by a real activity lifecycle (app-shell.md §15.4): the iOS phase edges, the
 * configuration-change exception, the resumed activity for `BiometricPrompt`, and synchronous privacy signals that
 * observe the new phase.
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

        monitor.onCaptureChanged(true)
        assertTrue(monitor.isCaptured.value)
        monitor.onCaptureChanged(true) // no change, no signal
        monitor.onCaptureChanged(false)
        assertFalse(monitor.isCaptured.value)
        monitor.onScreenshot()

        assertEquals(listOf(PrivacySignal.CaptureChanged, PrivacySignal.CaptureChanged, PrivacySignal.Screenshot), signals)
        assertEquals(1, screenshots)
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

    @Test
    fun aDestroyedActivityIsReleased() {
        val monitor = DefaultAppLifecycleMonitor()
        val controller = attached(monitor)
        controller.create().start().resume().pause().stop().destroy()
        assertNull(monitor.foregroundActivity)
        assertEquals(ScenePhase.Background, monitor.phase.value)
    }
}
