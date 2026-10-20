package com.bittool.signer.core.scanner.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.scanning.QrScanFrameGate
import com.bittool.signer.core.model.scanning.QrScanPurpose
import com.bittool.signer.core.scanner.CameraPermissionStatus
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The scanner chrome's states (iOS `QRScannerView.statusPanel` and top bar, `QRScannerView.swift:63-126`). */
@RunWith(RobolectricTestRunner::class)
class QrScannerContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val hint = "Point the camera at the QR code in your other wallet."
    private var resets = 0
    private var closes = 0
    private var settings = 0
    private var torchToggles = 0

    private fun chrome(
        permission: CameraPermissionStatus = CameraPermissionStatus.Granted,
        progress: Float? = 0f,
        status: String? = null,
        error: String? = null,
        cameraMessage: String? = null,
        torchAvailable: Boolean = false,
        torchOn: Boolean = false,
    ) = ScannerChrome(
        title = "Scan Request",
        hint = hint,
        progress = progress,
        status = status,
        error = error,
        permission = permission,
        cameraMessage = cameraMessage,
        torchAvailable = torchAvailable,
        torchOn = torchOn,
        animationsEnabled = false,
    )

    private fun show(initial: ScannerChrome): (ScannerChrome) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            QrScannerContent(
                chrome = state,
                onToggleTorch = { torchToggles++ },
                onOpenSettings = { settings++ },
                onReset = { resets++ },
                onClose = { closes++ },
            )
        }
        return { state = it }
    }

    @Test
    fun theHintShowsUntilTheSessionHasAStatus() {
        val update = show(chrome())
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals(hint)
        compose.onNodeWithTag(ScannerTags.PROGRESS).assertDoesNotExist()
        update(chrome(status = "Reading request…"))
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals("Reading request…")
    }

    @Test
    fun progressBetweenZeroAndOneShowsAPercentage() {
        val update = show(chrome(progress = 0.428f, status = "Keep the animated code in view · 4 parts"))
        compose.onNodeWithTag(ScannerTags.PROGRESS).assertTextEquals("42%")
        update(chrome(progress = 1f))
        compose.onNodeWithTag(ScannerTags.PROGRESS).assertDoesNotExist()
    }

    @Test
    fun aSessionErrorReplacesTheHintAndOffersScanAgain() {
        show(chrome(error = "Unsupported signing QR format", cameraMessage = "ignored while an error shows"))
        compose.onNodeWithTag(ScannerTags.PROTOCOL_ERROR).assertTextEquals("Unsupported signing QR format")
        compose.onNodeWithTag(ScannerTags.STATUS).assertDoesNotExist()
        compose.onNodeWithTag(ScannerTags.CAMERA_ERROR).assertDoesNotExist()
        compose.onNodeWithText("Scan Again").performClick()
        assertEquals(1, resets)
    }

    @Test
    fun aCameraProblemShowsUnderTheHint() {
        show(chrome(cameraMessage = "The camera is unavailable."))
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals(hint)
        compose.onNodeWithTag(ScannerTags.CAMERA_ERROR).assertTextEquals("The camera is unavailable.")
    }

    @Test
    fun deniedCameraAccessExplainsAndOpensSettings() {
        val update = show(chrome(permission = CameraPermissionStatus.Denied, error = "an error does not hide the access panel"))
        for (denied in listOf(CameraPermissionStatus.Denied, CameraPermissionStatus.PermanentlyDenied)) {
            update(chrome(permission = denied, error = "an error does not hide the access panel"))
            compose.onNodeWithText("Camera Access Needed").assertIsDisplayed()
            compose.onNodeWithText(
                "Allow camera access in Settings to scan QR codes, or import the request as a file or pasted text.",
            ).assertIsDisplayed()
            compose.onNodeWithTag(ScannerTags.PROTOCOL_ERROR).assertDoesNotExist()
            compose.onNodeWithText("Open Settings").performClick()
        }
        assertEquals(2, settings)
        update(chrome(permission = CameraPermissionStatus.Granted))
        compose.onNodeWithText("Camera Access Needed").assertDoesNotExist()
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals(hint)
    }

    @Test
    fun whileAskingTheHintShows() {
        show(chrome(permission = CameraPermissionStatus.NotAsked))
        compose.onNodeWithTag(ScannerTags.STATUS).assertTextEquals(hint)
        compose.onNodeWithText("Camera Access Needed").assertDoesNotExist()
    }

    @Test
    fun atLargeTextInLandscapeThePanelScrollsSoItsButtonStaysReachable() {
        // A phone in landscape at font scale 2: the recovery wording and "Scan Again" do not fit under the top bar.
        val long = QrScanFrameGate.Issue.NonText.english(QrScanPurpose.RecoveryPhrase).repeat(2)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.size(width = 640.dp, height = 340.dp)) {
                    QrScannerContent(
                        chrome = chrome(error = long),
                        onToggleTorch = {},
                        onOpenSettings = {},
                        onReset = { resets++ },
                        onClose = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(ScannerTags.RESET).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, resets)
        // The top bar stays where it is.
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun closeAndTorchCarryTheIosLabels() {
        val update = show(chrome(torchAvailable = false))
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closes)
        // Unavailable torch: invisible, inert and not announced (iOS opacity 0 + disabled).
        compose.onNodeWithContentDescription("Turn on flashlight").assertDoesNotExist()
        update(chrome(torchAvailable = true, torchOn = false))
        compose.onNodeWithContentDescription("Turn on flashlight").performClick()
        assertEquals(1, torchToggles)
        update(chrome(torchAvailable = true, torchOn = true))
        compose.onNodeWithContentDescription("Turn off flashlight").assertIsDisplayed()
        compose.onNodeWithText("Scan Request").assertIsDisplayed()
    }

    @Test
    fun theScannerDrawsWithTheDarkDesignSystemPaletteInALightApp() {
        // iOS forces the dark scheme in the scanner subtree only (QRScannerView.swift:48); the colours are the design
        // system's tokens (Brand.mint, ink, the dark labels and orange), not a private copy (docs/qa/backlog.md B11).
        var appPalette: SignerColors? = null
        var cameraPalette: SignerColors? = null
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                appPalette = SignerTheme.colors
                QrScannerContent(
                    chrome = chrome(),
                    onToggleTorch = {},
                    onOpenSettings = {},
                    onReset = {},
                    onClose = {},
                    camera = { cameraPalette = SignerTheme.colors },
                )
            }
        }
        compose.waitForIdle()
        assertSame(SignerColors.Light, appPalette)
        assertSame(SignerColors.Dark, cameraPalette)
    }
}
