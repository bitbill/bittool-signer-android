package com.bittool.signer.feature.wallets.security

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The security pages (and Connect, same frame) keep their form above the keyboard without counting the navigation bar
 * twice: the keyboard inset already covers the bar, so the scrolled-to-end content ends exactly the page's bottom
 * margins above the keyboard, not a navigation bar higher (`consumeWindowInsets(padding)` before `imePadding()`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class SecurityPageInsetsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theKeyboardDoesNotCountTheNavigationBarTwice() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                SecurityPage(title = "Repair Wallet", busy = false, onCancel = {}, toast = rememberStatusToastState()) {
                    Box(Modifier.fillMaxWidth().height(3_000.dp).testTag("form"))
                }
            }
        }
        // mdpi: 1 px = 1 dp. A 48 dp navigation bar and a 300 dp keyboard (which reaches the bottom of the screen).
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, KEYBOARD))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        repeat(12) { compose.onNodeWithTag("form").performTouchInput { swipeUp() } }
        compose.waitForIdle()
        val rootBottom = compose.onRoot().getBoundsInRoot().bottom
        val formBottom = compose.onNodeWithTag("form").getBoundsInRoot().bottom
        // Under the form: the page's own bottom margins (8 dp + the 30 dp list inset), then the keyboard; counting the
        // navigation bar inside the keyboard twice would leave the form 48 dp higher (414 dp instead of 462 dp).
        assertEquals(rootBottom - KEYBOARD.dp - 8.dp - 30.dp, formBottom)
    }

    private companion object {
        const val NAV_BAR = 48
        const val KEYBOARD = 300
    }
}
