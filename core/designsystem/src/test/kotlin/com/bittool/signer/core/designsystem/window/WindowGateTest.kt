package com.bittool.signer.core.designsystem.window

import android.Manifest
import android.app.Application
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.theme.SignerTheme
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The window gate (ARCHITECTURE §1.10 row 17, Ownbit `WindowGate.kt` semantics): a dialog or sheet requested while the
 * gate is closed (App Lock up) appears only once it opens; one already shown stays when the gate closes again.
 */
@RunWith(RobolectricTestRunner::class)
class WindowGateTest {
    @get:Rule
    val compose = createComposeRule()

    private var open by mutableStateOf(false)

    @Test
    fun aDialogRequestedWhileClosedAppearsOnlyAfterRelease() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalWindowGateOpen provides open) {
                    SignerAlertDialog(
                        title = "Unable to Continue",
                        text = "The engine stopped.",
                        confirm = DialogAction("OK") {},
                        onDismissRequest = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Unable to Continue").assertDoesNotExist()
        open = true
        compose.onNodeWithText("Unable to Continue").assertExists()
        compose.onNodeWithText("The engine stopped.").assertExists()
    }

    @Test
    fun aShownDialogStaysWhenTheGateClosesAgain() {
        open = true
        var confirmed = 0
        compose.setContent {
            SignerTheme(darkTheme = true, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalWindowGateOpen provides open) {
                    SignerAlertDialog(
                        title = "Discard partial signatures?",
                        text = null,
                        confirm = DialogAction("Reject Request", destructive = true) { confirmed++ },
                        dismiss = DialogAction("Cancel") {},
                        onDismissRequest = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Discard partial signatures?").assertExists()
        open = false
        compose.waitForIdle()
        compose.onNodeWithText("Discard partial signatures?").assertExists()
        compose.onNodeWithText("Reject Request").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun aSheetWaitsForTheGateToo() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalWindowGateOpen provides open) {
                    SignerBottomSheet(onDismissRequest = {}) { Text("Paste Request") }
                }
            }
        }
        compose.onNodeWithText("Paste Request").assertDoesNotExist()
        open = true
        compose.onNodeWithText("Paste Request").assertExists()
        open = false
        compose.waitForIdle()
        compose.onNodeWithText("Paste Request").assertExists()
    }

    /**
     * gaps G4.1 and ARCHITECTURE §4.4: dialogs and sheets are windows of their own, so each one hides other apps'
     * overlays (API 31+) and, when it can show a secret, is `FLAG_SECURE` itself.
     */
    @Test
    fun dialogAndSheetWindowsHideOverlaysAndCanBeSecure() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS) // normal protection: granted at install
        open = true
        var dialogView: View? = null
        var sheetView: View? = null
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalWindowGateOpen provides open) {
                    SignerAlertDialog(
                        title = "Wallet Password",
                        text = null,
                        confirm = DialogAction("OK") {},
                        secure = true,
                        onDismissRequest = {},
                    ) { dialogView = LocalView.current }
                    SignerBottomSheet(onDismissRequest = {}, secure = true) {
                        sheetView = LocalView.current
                        Text("Authorize Signature")
                    }
                }
            }
        }
        compose.waitForIdle()
        for ((name, view) in listOf("dialog" to dialogView, "sheet" to sheetView)) {
            val window = assertNotNull(assertNotNull(view, "$name composed").dialogWindow(), "$name has its own window")
            val attributes = window.attributes
            assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0, "$name is FLAG_SECURE")
            val privateFlags = WindowManager.LayoutParams::class.java.getField("privateFlags").getInt(attributes)
            assertTrue(privateFlags and SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS != 0, "$name hides foreign overlays")
        }
    }

    @Test
    fun windowsOutsideAGateAreNeverHeld() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                SignerAlertDialog(title = "Wallet Password", text = null, confirm = DialogAction("OK") {}, onDismissRequest = {})
            }
        }
        compose.onNodeWithText("Wallet Password").assertExists()
    }

    private companion object {
        /** `WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (hidden), what `setHideOverlayWindows` sets. */
        const val SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 0x00080000
    }
}
