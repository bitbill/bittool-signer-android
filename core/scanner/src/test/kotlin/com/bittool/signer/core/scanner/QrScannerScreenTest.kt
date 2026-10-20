package com.bittool.signer.core.scanner

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.scanning.QrScanFrameGate
import com.bittool.signer.core.model.scanning.QrScanPurpose
import com.bittool.signer.core.scanner.scan.ScannerTags
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The scanner screen around the camera: asking on appearance, the denied panel, a grant made in Settings picked up
 * on resume, camera problems and the purpose's gate wording (scan-sign.md §6.4–§6.8, §23.5).
 */
@RunWith(RobolectricTestRunner::class)
class QrScannerScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val hint = "Point the camera at the QR code in your other wallet."
    private val application get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun noCameraHardware() {
        // Robolectric has no camera: once authorised, the screen must say so instead of showing a black page.
        shadowOf(application.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
    }

    private fun showScanner(error: String? = null, permission: (@Composable () -> CameraPermissionState)? = null) {
        compose.setContent {
            val title = "Scan Request"
            val purpose = QrScanPurpose.SigningRequest
            if (permission == null) {
                QrScannerScreen(title, hint, purpose, progress = null, status = null, error = error, onReset = {}, onClose = {}, onScan = {})
            } else {
                QrScannerScreen(
                    title, hint, purpose, progress = null, status = null, error = error,
                    onReset = {}, onClose = {}, onScan = {}, permission = permission(),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theScannerAsksForTheCameraWhenItAppears() {
        showScanner()
        val request = assertNotNull(shadowOf(compose.activity).lastRequestedPermission)
        assertEquals(listOf(Manifest.permission.CAMERA), request.requestedPermissions.toList())
    }

    @Test
    fun aDenialShowsTheAccessPanelAndAGrantMadeInSettingsStartsTheCamera() {
        lateinit var permission: CameraPermissionState
        showScanner(permission = { rememberCameraPermissionState().also { permission = it } })
        // The system dialog answers "Don't allow".
        compose.runOnUiThread { permission.onRequestResult(granted = false) }
        compose.waitForIdle()
        compose.onNodeWithText("Camera Access Needed").assertExists()
        compose.onNodeWithText("Open Settings").assertExists()
        compose.onNodeWithTag(ScannerTags.STATUS).assertDoesNotExist()

        // Back from Settings with the permission on: the camera starts without reopening the scanner.
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(CameraPermissionStatus.Granted, permission.status)
        compose.onNodeWithText("Camera Access Needed").assertDoesNotExist()
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals(hint)
        compose.onNodeWithTag(ScannerTags.CAMERA_ERROR)
            .assertTextEquals("No camera is available. Import the request as a file instead.")
    }

    @Test
    fun anAuthorisedScannerDoesNotAsk() {
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        showScanner()
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals(hint)
        compose.onNodeWithTag(ScannerTags.CAMERA_ERROR)
            .assertTextEquals("No camera is available. Import the request as a file instead.")
    }

    @Test
    fun aSessionErrorIsShownWithScanAgain() {
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        showScanner(error = "Unsupported signing QR format")
        compose.onNodeWithTag(ScannerTags.PROTOCOL_ERROR).assertTextEquals("Unsupported signing QR format")
        compose.onNodeWithTag(ScannerTags.RESET).assertExists()
    }

    /** A granted scanner whose camera is a test double: [deliver] plays one frame's values on the main thread. */
    private inner class FakeCameraScanner(purpose: QrScanPurpose, initialError: String? = null) {
        var error by mutableStateOf(initialError)
        var shown by mutableStateOf(true)
        var now = 100_000L
        val scanned = ArrayList<String>()
        private var sink: ((List<String?>) -> Unit)? = null
        lateinit var view: View

        init {
            shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
            compose.setContent {
                view = LocalView.current
                if (shown) {
                    QrScannerScreen(
                        title = "Scan Recovery QR",
                        hint = hint,
                        purpose = purpose,
                        progress = null,
                        status = null,
                        error = error,
                        onReset = { error = null },
                        onClose = {},
                        onScan = { scanned += it },
                        permission = rememberCameraPermissionState(),
                        clock = { now },
                        camera = { _, onValues -> SideEffect { sink = onValues } },
                    )
                }
            }
            compose.waitForIdle()
        }

        fun deliver(vararg values: String?) {
            compose.runOnUiThread { assertNotNull(sink, "camera not composed")(values.toList()) }
            compose.waitForIdle()
        }
    }

    @Test
    fun theCameraKeepsReadingWhileAnErrorShows() {
        // iOS keeps the camera running under an error (QRScannerView.swift:29-31). Setup's recovery scanner relies on
        // it: after "This QR code does not contain a recovery phrase." a valid phrase QR is taken without "Scan Again"
        // (WalletSetupFlow.swift:218-228). Ignoring frames meanwhile is the signing session's own rule (ScanTab.swift:54).
        val wrong = "This QR code does not contain a recovery phrase."
        val scanner = FakeCameraScanner(QrScanPurpose.RecoveryPhrase, initialError = wrong)
        compose.onNodeWithTag(ScannerTags.PROTOCOL_ERROR).assertTextEquals(wrong)
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        scanner.deliver(phrase)
        assertEquals(listOf(phrase), scanner.scanned)
    }

    @Test
    fun gateRejectionsUseThePurposeWordingAndAnAcceptedCodeClearsThem() {
        val scanner = FakeCameraScanner(QrScanPurpose.RecoveryPhrase)
        // Only unreadable codes in the frame (e.g. a Compact SeedQR): one non-text observation.
        scanner.deliver(null)
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals(hint)
        compose.onNodeWithTag(ScannerTags.CAMERA_ERROR)
            .assertTextEquals(QrScanFrameGate.Issue.NonText.english(QrScanPurpose.RecoveryPhrase))
        scanner.now += 40
        scanner.deliver(" \n\t")
        compose.onNodeWithTag(ScannerTags.CAMERA_ERROR)
            .assertTextEquals(QrScanFrameGate.Issue.Empty.english(QrScanPurpose.RecoveryPhrase))
        scanner.now += 40
        scanner.deliver(" \nUR:BYTES/TESTFRAME\n")
        assertEquals(listOf(" \nUR:BYTES/TESTFRAME\n"), scanner.scanned)
        compose.onNodeWithTag(ScannerTags.CAMERA_ERROR).assertDoesNotExist()
    }

    @Test
    fun aCodeHeldInViewReachesTheCallerOncePerSecondOnTheMonotonicClock() {
        val scanner = FakeCameraScanner(QrScanPurpose.SigningRequest)
        scanner.deliver("UR:BYTES/HELD")
        scanner.now += 999
        scanner.deliver("UR:BYTES/HELD")
        assertEquals(1, scanner.scanned.size)
        scanner.now += 1
        scanner.deliver("UR:BYTES/HELD")
        // The next animated part passes at once.
        scanner.now += 33
        scanner.deliver("UR:BYTES/NEXT")
        assertEquals(listOf("UR:BYTES/HELD", "UR:BYTES/HELD", "UR:BYTES/NEXT"), scanner.scanned)
    }

    @Test
    fun aNewErrorPlaysTheRejectHapticButAnErrorAlreadyShownDoesNot() {
        // iOS `.sensoryFeedback(.error, trigger: error) { _, new in new != nil }` (QRScannerView.swift:50).
        val scanner = FakeCameraScanner(QrScanPurpose.SigningRequest, initialError = "Shown on arrival")
        assertEquals(NO_HAPTIC, shadowOf(scanner.view).lastHapticFeedbackPerformed())
        scanner.error = null
        compose.waitForIdle()
        assertEquals(NO_HAPTIC, shadowOf(scanner.view).lastHapticFeedbackPerformed())
        scanner.error = "Unsupported signing QR format"
        compose.waitForIdle()
        assertEquals(HapticFeedbackConstants.REJECT, shadowOf(scanner.view).lastHapticFeedbackPerformed())
    }

    @Test
    fun barIconsAreLightWhileTheScannerShowsAndTheAppsComeBackAfter() {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        compose.runOnUiThread {
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
        val scanner = FakeCameraScanner(QrScanPurpose.SigningRequest)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
        scanner.shown = false
        compose.waitForIdle()
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun gateMessagesUseTheExactIosEnglishForEachPurpose() {
        val resources = compose.activity.resources
        for (purpose in QrScanPurpose.entries) {
            for (issue in QrScanFrameGate.Issue.entries) {
                assertEquals(issue.english(purpose), resources.getString(issue.messageRes(purpose)), "$purpose $issue")
            }
        }
    }

    private companion object {
        /** Robolectric's value before any `performHapticFeedback`. */
        const val NO_HAPTIC = -1
    }
}
