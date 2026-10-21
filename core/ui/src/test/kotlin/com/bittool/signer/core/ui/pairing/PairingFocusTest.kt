package com.bittool.signer.core.ui.pairing

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AccountProfile
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.ui.WindowSignals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keyboard focus on the Connect page's controls with a hardware keyboard: nothing is left focused where iOS leaves
 * nothing focused, so no focus ring lands on the page's first control, the back arrow (QA regression-03, backlog B30).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA])
class PairingFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View

    /** The page: its first control (the back arrow), then [body]. */
    private fun page(body: @Composable () -> Unit) {
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column {
                    Button(onClick = {}, modifier = Modifier.testTag("back")) { Text("Back") }
                    body()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertNothingFocused() {
        compose.onNodeWithTag("back").assertIsNotFocused()
        assertNull(view.rootView.findFocus(), "No focus ring anywhere on the page")
    }

    /**
     * Done (or Enter) in the TRON address index ends the editing with nothing focused (iOS `editing = false`,
     * `ConnectView.swift:498-532`). A plain `FocusManager.clearFocus()` handed the focus to the back arrow in keyboard
     * mode.
     */
    @Test
    fun doneInTheAddressIndexWithAHardwareKeyboardLeavesNothingFocused() {
        var index by mutableLongStateOf(0L)
        page { AddressIndexControl(index = index, onIndexChange = { index = it }, pathPrefix = "m/44'/195'/0'/0/") }
        WindowSignals.setTouchMode(view, inTouchMode = false)
        val field = compose.onNodeWithTag("connect.tronIndex.field")
        field.requestFocus()
        compose.waitForIdle()
        field.assertIsFocused()
        field.performTextReplacement("12")
        field.performImeAction()
        compose.waitForIdle()

        assertEquals(12L, index)
        field.assertIsNotFocused()
        assertNothingFocused()
    }

    /** The profile menu (a `SignerDropdownMenu`): an option chosen with the keyboard leaves nothing focused. */
    @Test
    fun anOptionOfTheProfileMenuChosenWithTheKeyboardLeavesNothingFocused() {
        var selection by mutableStateOf(AccountProfiles.bitcoinSingle.first())
        page {
            ProfileMenu(
                title = "Address type",
                options = AccountProfiles.bitcoinSingle,
                selection = selection,
                label = AccountProfile::title,
                detail = AccountProfile::detail,
                onSelect = { selection = it },
            )
        }
        WindowSignals.setTouchMode(view, inTouchMode = true)
        WindowSignals.windowFocus(view, hasFocus = true)
        view.rootView.findFocus()?.clearFocus()
        compose.onNodeWithTag("profile.menu").performClick()
        compose.waitForIdle()
        val menu = WindowSignals.topWindowRoot()
        assertNotSame(view.rootView, menu)
        WindowSignals.windowFocus(view, hasFocus = false)
        WindowSignals.leaveTouchModeIn(menu, view)
        compose.onNodeWithText("Taproot").performClick()
        compose.waitForIdle()
        WindowSignals.windowFocus(view, hasFocus = true)
        compose.waitForIdle()

        assertEquals("taproot", selection.id)
        assertNothingFocused()
    }
}
