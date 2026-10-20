package com.bittool.signer.core.scanner

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.scanning.QrScanFrameGate
import com.bittool.signer.core.model.scanning.QrScanPurpose
import com.bittool.signer.core.scanner.scan.ScannerTags
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun gateMessagesUseTheExactIosEnglishForEachPurpose() {
        val resources = compose.activity.resources
        for (purpose in QrScanPurpose.entries) {
            for (issue in QrScanFrameGate.Issue.entries) {
                assertEquals(issue.english(purpose), resources.getString(issue.messageRes(purpose)), "$purpose $issue")
            }
        }
    }
}
