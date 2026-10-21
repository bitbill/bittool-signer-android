package com.bittool.signer.core.ui.keyboard

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.ui.LANDSCAPE
import com.bittool.signer.core.ui.PORTRAIT
import com.bittool.signer.core.ui.rotateItself
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA activity-keyboard-01: the app's activity handles rotation itself, so the keyboard stays composed through a
 * rotation and must take the new window's rows, both ways: shorter rows in landscape (the page keeps room for the
 * editor), full rows again in portrait. Below API 33 Compose's `LocalWindowInfo.containerDpSize` keeps the previous
 * orientation's size there (see `windowHeight`); the rotation is delivered in the platform's order (`rotateItself`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S, Build.VERSION_CODES.BAKLAVA], qualifiers = "$PORTRAIT-xhdpi")
class Bip39KeyboardRotationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    Bip39Keyboard(
                        state = TextFieldState(),
                        suggestions = KeyboardSuggestions.None,
                        onSuggestion = {},
                        onSwitchToSystemKeyboard = {},
                        onDone = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** A key cell is its row's full height. */
    private fun row(): Int = compose.onNodeWithTag(Bip39KeyboardTags.letter('q')).fetchSemanticsNode().size.height

    private fun rowFor(windowHeight: Int): Int = with(compose.density) { keyboardMetrics(windowHeight.dp).row.roundToPx() }

    private fun rotate(qualifiers: String) {
        compose.runOnUiThread { compose.activity.rotateItself(qualifiers) }
        compose.waitForIdle()
    }

    @Test
    fun theRowsFollowARotationTheActivityHandlesItselfBothWays() {
        show()
        assertEquals(rowFor(852), row(), "portrait: full rows")
        rotate(LANDSCAPE)
        assertEquals(rowFor(393), row(), "landscape after the rotation: the short window's rows")
        rotate(PORTRAIT)
        assertEquals(rowFor(852), row(), "portrait again: full rows")
    }

    /** Started in landscape (a cold start there was already right), then turned to portrait. */
    @Test
    @Config(qualifiers = "$LANDSCAPE-xhdpi")
    fun aKeyboardShownInLandscapeGetsFullRowsInPortrait() {
        show()
        assertEquals(rowFor(393), row(), "landscape: the short window's rows")
        rotate(PORTRAIT)
        assertEquals(rowFor(852), row(), "portrait after the rotation: full rows")
    }
}
