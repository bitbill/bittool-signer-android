package com.bittool.signer.core.designsystem.window

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    @Test
    fun windowsOutsideAGateAreNeverHeld() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                SignerAlertDialog(title = "Wallet Password", text = null, confirm = DialogAction("OK") {}, onDismissRequest = {})
            }
        }
        compose.onNodeWithText("Wallet Password").assertExists()
    }
}
