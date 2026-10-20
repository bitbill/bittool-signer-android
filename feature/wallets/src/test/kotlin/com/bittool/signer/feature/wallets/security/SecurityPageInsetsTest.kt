package com.bittool.signer.feature.wallets.security

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ConnectOptions
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.connect.ConnectActions
import com.bittool.signer.feature.wallets.connect.ConnectContent
import com.bittool.signer.feature.wallets.connect.ConnectUiState
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The security pages and Pair (TRON index field) keep their content above the keyboard without counting the navigation
 * bar twice: the keyboard inset already covers the bar, so the scrolled-to-end content ends exactly the page's bottom
 * margins above the keyboard, not a navigation bar higher (`consumeWindowInsets(padding)` before `imePadding()`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class SecurityPageInsetsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theKeyboardDoesNotCountTheNavigationBarTwiceOnASecurityPage() {
        // Under the form: the page's own bottom margins (8 dp + the 30 dp list inset), then the keyboard; counting the
        // navigation bar inside the keyboard twice would leave the form 48 dp higher (414 dp instead of 462 dp).
        assertContentEndsAboveTheKeyboard(lastTag = "form", bottomMargin = 8.dp + 30.dp) {
            SecurityPage(title = "Repair Wallet", busy = false, onCancel = {}, toast = rememberStatusToastState()) {
                Box(Modifier.fillMaxWidth().height(3_000.dp).testTag("form"))
            }
        }
    }

    @Test
    fun theKeyboardDoesNotCountTheNavigationBarTwiceOnPair() {
        // TRON Coin Level (the Pair page with a keyboard): the last block is "Works with", then the 24 dp bottom margin.
        assertContentEndsAboveTheKeyboard(lastTag = "connect.wallets", bottomMargin = 24.dp) {
            ConnectContent(
                uiState = ConnectUiState(
                    level = PairingLevel.Coin,
                    options = ConnectOptions(chain = Chain.Tron),
                    coin = WalletFixtures.coinPayload(path = "m/44'/195'/0'/0/0", address = "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", mode = "address"),
                    masterFingerprint = "73C5DA0A",
                ),
                toast = rememberStatusToastState(),
                actions = ConnectActions(),
            )
        }
    }

    private fun assertContentEndsAboveTheKeyboard(lastTag: String, bottomMargin: Dp, page: @Composable () -> Unit) {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { page() }
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
        compose.onNodeWithTag(lastTag).performScrollTo()
        repeat(12) { compose.onNodeWithTag(lastTag).performTouchInput { swipeUp() } }
        compose.waitForIdle()
        val rootBottom = compose.onRoot().getBoundsInRoot().bottom
        val lastBottom = compose.onNodeWithTag(lastTag).getBoundsInRoot().bottom
        assertEquals(rootBottom - KEYBOARD.dp - bottomMargin, lastBottom)
    }

    private companion object {
        const val NAV_BAR = 48
        const val KEYBOARD = 300
    }
}
