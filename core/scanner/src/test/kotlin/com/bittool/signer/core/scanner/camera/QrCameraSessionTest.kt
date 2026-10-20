package com.bittool.signer.core.scanner.camera

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.camera.core.CameraState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Restarting the camera after a start that failed (docs/qa/backlog.md B11). iOS composes its camera only while the scene
 * is active and builds a new capture session on every activation (`QRScannerView.swift:29`, `Coordinator.start`), so a
 * camera that could not start is tried again each time the user comes back. CameraX reopens a camera that was bound by
 * itself (the lifecycle owner resumes); a start that bound nothing must be redone by the session.
 */
@RunWith(RobolectricTestRunner::class)
class QrCameraSessionTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val host = TestHost()
    private val state = QrCameraState()
    private val sessions = ArrayList<QrCameraSession>()

    /** How often the session asked CameraX for its provider, i.e. how many starts it made. */
    private var starts = 0

    @Before
    fun aCameraIsPresent() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    @After
    fun releaseTheSessions() {
        sessions.forEach(QrCameraSession::release)
    }

    private fun session(
        awaitProvider: suspend (Context) -> ProcessCameraProvider = {
            starts++
            throw IllegalStateException("CameraX could not be initialised")
        },
    ): QrCameraSession = QrCameraSession(
        context = context,
        hostLifecycle = host.lifecycle,
        previewView = QrCameraSession.previewView(context),
        state = state,
        onValues = {},
        awaitProvider = awaitProvider,
    ).also { sessions += it }

    private fun runMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** The app goes to the background (or the shade comes down) and comes back. */
    private fun pauseAndResume() {
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        runMainLooper()
        host.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        runMainLooper()
    }

    @Test
    fun aCameraThatFailedToStartIsStartedAgainEachTimeTheScreenResumes() {
        host.registry.currentState = Lifecycle.State.RESUMED
        session().start()
        runMainLooper()
        assertEquals(1, starts, "starting on a resumed screen starts once")
        assertEquals(CameraIssue.Unavailable, state.issue)

        pauseAndResume()
        assertEquals(2, starts)
        pauseAndResume()
        assertEquals(3, starts)
        // Still failing: the message stays (it is not cleared while the new attempt runs).
        assertEquals(CameraIssue.Unavailable, state.issue)
    }

    @Test
    fun aStartThatFailedBeforeTheScreenResumedIsRetriedWhenItResumes() {
        // A navigation destination is composed while its entry is only STARTED (during the enter transition).
        host.registry.currentState = Lifecycle.State.STARTED
        session().start()
        runMainLooper()
        assertEquals(1, starts)

        host.registry.currentState = Lifecycle.State.RESUMED
        runMainLooper()
        assertEquals(2, starts)
    }

    @Test
    fun aDeviceThatReportedNoCameraIsAskedAgainOnResume() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        host.registry.currentState = Lifecycle.State.RESUMED
        session().start()
        runMainLooper()
        assertEquals(CameraIssue.NoCamera, state.issue)
        assertEquals(0, starts)

        // For example an external camera connected while the app was in the background.
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
        pauseAndResume()
        assertEquals(1, starts)
    }

    @Test
    fun aStartStillWaitingForCameraXIsNotStartedTwice() {
        host.registry.currentState = Lifecycle.State.RESUMED
        session {
            starts++
            awaitCancellation()
        }.start()
        runMainLooper()
        pauseAndResume()
        pauseAndResume()
        assertEquals(1, starts)
        assertNull(state.issue)
    }

    @Test
    fun aReleasedSessionIsNeverStartedAgain() {
        host.registry.currentState = Lifecycle.State.RESUMED
        val session = session()
        session.start()
        runMainLooper()
        session.release()
        pauseAndResume()
        assertEquals(1, starts)
    }

    @Test
    fun aCameraThatOpensAfterARetryClearsTheProblemOfTheEarlierStart() {
        // The retry bound a camera: once it opens, "No camera is available" or "The camera is unavailable" goes.
        val open = CameraState.create(CameraState.Type.OPEN)
        assertNull(QrCameraSession.issueAfter(CameraIssue.NoCamera, open, scanning = true))
        assertNull(QrCameraSession.issueAfter(CameraIssue.Unavailable, open, scanning = true))
        assertNull(QrCameraSession.issueAfter(null, open, scanning = true))
        // Only the preview could be bound: scanning stays off, whatever the earlier start reported.
        assertEquals(CameraIssue.ScanningUnavailable, QrCameraSession.issueAfter(CameraIssue.NoCamera, open, scanning = false))
        assertEquals(
            CameraIssue.ScanningUnavailable,
            QrCameraSession.issueAfter(CameraIssue.ScanningUnavailable, open, scanning = false),
        )
    }

    @Test
    fun aBoundCameraThatFailsIsUnavailableEvenAfterAnEarlierStartFoundNone() {
        val inUse = CameraState.create(
            CameraState.Type.PENDING_OPEN,
            CameraState.StateError.create(CameraState.ERROR_CAMERA_IN_USE),
        )
        assertEquals(CameraIssue.Unavailable, QrCameraSession.issueAfter(CameraIssue.NoCamera, inUse, scanning = true))
        assertEquals(CameraIssue.Unavailable, QrCameraSession.issueAfter(null, inUse, scanning = true))
        assertEquals(CameraIssue.Unavailable, QrCameraSession.issueAfter(CameraIssue.ScanningUnavailable, inUse, scanning = false))
        // While CameraX opens the camera again the message stays; it goes only when the camera is open.
        val reopening = listOf(CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING, CameraState.Type.CLOSING, CameraState.Type.CLOSED)
        for (type in reopening) {
            val issue = QrCameraSession.issueAfter(CameraIssue.Unavailable, CameraState.create(type), scanning = true)
            assertEquals(CameraIssue.Unavailable, issue, "$type")
        }
    }

    /** The screen's lifecycle (a navigation entry or the activity), driven by the test on the main thread. */
    private class TestHost : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
