package com.bittool.signer.feature.wallets.common

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.wallets.WindowSignals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The top-bar icon actions' tooltip (QA wallets-12): shown on a long press, never because the button has the keyboard
 * focus. Material 3 keeps a focus-opened tooltip up for as long as the focus stays, so the focus Android gives the first
 * control of a window in keyboard mode left "Scan" with its tooltip pinned.
 */
@RunWith(RobolectricTestRunner::class)
class IconActionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View

    private fun show() = compose.setContent {
        view = LocalView.current
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
            IconAction(icon = SignerIcons.QrCodeScanner, label = "Scan", onClick = {})
        }
    }

    @Test
    fun aKeyboardFocusedActionShowsNoTooltip() {
        show()
        compose.waitForIdle()
        WindowSignals.setTouchMode(view, inTouchMode = false)
        compose.onNodeWithContentDescription("Scan").requestFocus().assertIsFocused()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodesWithText("Scan").assertCountEquals(0)
    }

    @Test
    fun aLongPressStillShowsTheTooltip() {
        show()
        compose.waitForIdle()
        WindowSignals.setTouchMode(view, inTouchMode = true)
        compose.onNodeWithContentDescription("Scan").performTouchInput { longClick() }
        compose.onAllNodesWithText("Scan").assertCountEquals(1)
    }
}
