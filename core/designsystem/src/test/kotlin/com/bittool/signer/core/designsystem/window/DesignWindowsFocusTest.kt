package com.bittool.signer.core.designsystem.window

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.component.SignerDropdownMenu
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backlog B30 (rest of QA wallets-12): every dialog, sheet and menu of the design system gives the page under it no
 * stray keyboard focus. After a window used with a hardware keyboard goes, Android focuses the page's first control (in
 * the app: the back arrow, or "Scan" with its tooltip) unless something there had the focus; with
 * [SignerAlertDialog], [SignerBottomSheet] and [SignerDropdownMenu] the page comes back with nothing focused, or with
 * the control that had the focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA])
class DesignWindowsFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf(false)
    private lateinit var root: View

    /** The page: a first control (the back arrow) and the control that opens [window]. */
    private fun page(window: @Composable () -> Unit) {
        compose.setContent {
            root = LocalView.current.rootView
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column {
                    Button(onClick = {}, modifier = Modifier.testTag("first")) { Text("Back") }
                    Box {
                        Button(onClick = { shown = true }, modifier = Modifier.testTag("open")) { Text("Rename") }
                        if (shown) window()
                    }
                }
            }
        }
        compose.waitForIdle()
        WindowSignals.setTouchMode(root, inTouchMode = true)
        WindowSignals.windowFocus(root, hasFocus = true)
        root.findFocus()?.clearFocus()
        assertNull(root.findFocus(), "In touch mode nothing is focused")
    }

    /**
     * Opens the window, uses the keyboard in it (Enter on its button), then closes it. [pageUnfocused]: nothing on the
     * page had the focus when the window opened.
     */
    private fun openUseWithTheKeyboardAndClose(pageUnfocused: Boolean = true) {
        shown = true
        compose.waitForIdle()
        val above = WindowSignals.topWindowRoot()
        assertNotSame(root, above, "The window is a window of its own")
        WindowSignals.windowFocus(root, hasFocus = false)
        WindowSignals.leaveTouchModeIn(above, root)
        if (pageUnfocused) assertNull(root.findFocus(), "Nothing on the page is focused under the window (API 33+)")
        shown = false
        compose.waitForIdle()
        WindowSignals.windowFocus(root, hasFocus = true)
        compose.waitForIdle()
    }

    private fun assertNothingFocusedOnThePage() {
        assertNull(root.findFocus(), "No focus ring on the page's first control")
        compose.onNodeWithTag("first").assertIsNotFocused()
    }

    @Test
    fun withoutTheHoldAPlainDialogLeavesTheFocusOnThePagesFirstControl() {
        // The platform behaviour the design system's windows avoid (a Material dialog of its own).
        shown = false
        compose.setContent {
            root = LocalView.current.rootView
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column {
                    Button(onClick = {}, modifier = Modifier.testTag("first")) { Text("Back") }
                    if (shown) {
                        AlertDialog(
                            onDismissRequest = { shown = false },
                            confirmButton = { TextButton(onClick = { shown = false }) { Text("Save") } },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        WindowSignals.setTouchMode(root, inTouchMode = true)
        WindowSignals.windowFocus(root, hasFocus = true)
        root.findFocus()?.clearFocus()
        shown = true
        compose.waitForIdle()
        val above = WindowSignals.topWindowRoot()
        WindowSignals.windowFocus(root, hasFocus = false)
        WindowSignals.leaveTouchModeIn(above, root)
        shown = false
        compose.waitForIdle()
        WindowSignals.windowFocus(root, hasFocus = true)
        compose.waitForIdle()
        assertNotNull(root.findFocus())
        compose.onNodeWithTag("first").assertIsFocused()
    }

    @Test
    fun anAlertConfirmedWithTheKeyboardLeavesNothingFocused() {
        page {
            SignerAlertDialog(
                title = "Rename Wallet",
                text = "The fingerprint and addresses stay the same.",
                confirm = DialogAction("Save") { shown = false },
                dismiss = DialogAction("Cancel") { shown = false },
                onDismissRequest = { shown = false },
            )
        }
        openUseWithTheKeyboardAndClose()
        assertNothingFocusedOnThePage()
    }

    @Test
    fun aSheetClosedWithTheKeyboardLeavesNothingFocused() {
        page {
            SignerBottomSheet(onDismissRequest = { shown = false }) {
                TextButton(onClick = { dismiss() }) { Text("Done") }
            }
        }
        openUseWithTheKeyboardAndClose()
        assertNothingFocusedOnThePage()
    }

    @Test
    fun aMenuItemChosenWithTheKeyboardLeavesNothingFocused() {
        page {
            SignerDropdownMenu(expanded = true, onDismissRequest = { shown = false }) {
                DropdownMenuItem(text = { Text("Copy") }, onClick = { shown = false })
            }
        }
        openUseWithTheKeyboardAndClose()
        assertNothingFocusedOnThePage()
    }

    @Test
    fun theControlThatOpenedAWindowWithTheKeyboardGetsTheFocusBack() {
        page {
            SignerAlertDialog(
                title = "Rename Wallet",
                text = null,
                confirm = DialogAction("Save") { shown = false },
                onDismissRequest = { shown = false },
            )
        }
        // Keyboard mode on the page, and the opening control focused (Tab, then Enter).
        WindowSignals.setTouchMode(root, inTouchMode = false)
        compose.onNodeWithTag("open").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithTag("open").assertIsFocused()
        openUseWithTheKeyboardAndClose(pageUnfocused = false)
        compose.onNodeWithTag("open").assertIsFocused()
        compose.onNodeWithTag("first").assertIsNotFocused()
    }
}
