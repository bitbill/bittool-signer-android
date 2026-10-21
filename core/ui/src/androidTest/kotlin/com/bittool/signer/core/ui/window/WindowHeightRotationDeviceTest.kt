package com.bittool.signer.core.ui.window

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.ui.keyboard.Bip39Keyboard
import com.bittool.signer.core.ui.keyboard.Bip39KeyboardTags
import com.bittool.signer.core.ui.keyboard.KeyboardSuggestions
import com.bittool.signer.core.ui.keyboard.keyboardMetrics
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An activity that handles rotation itself, with `MainActivity`'s `configChanges` (androidTest manifest). */
class RotatingTestActivity : ComponentActivity()

/**
 * QA activity-keyboard-01 on the device: a real rotation of an activity that handles it itself, as the app's does. The
 * activity turns itself (`requestedOrientation`, no input is injected, nothing outside it is driven) to landscape and
 * back; [windowHeight] follows both turns and the in-app keyboard takes the short window's rows in landscape and its
 * full rows again in portrait. The Robolectric `Bip39KeyboardRotationTest` replays the platform's delivery order; this
 * checks it on the platform. What `LocalWindowInfo.containerDpSize` reported is logged (tag `WindowHeightRotation`):
 * below API 33 it stays one orientation behind.
 */
@RunWith(AndroidJUnit4::class)
class WindowHeightRotationDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<RotatingTestActivity>()

    // Written by the composition (main thread), read while waiting (the test thread).
    @Volatile private var height: Dp = 0.dp
    @Volatile private var container: DpSize = DpSize.Zero

    private fun turn(requested: Int, landscape: Boolean) {
        compose.runOnUiThread { compose.activity.requestedOrientation = requested }
        val orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(TURN_TIMEOUT_MS) {
            val decor = compose.activity.window.decorView
            compose.activity.resources.configuration.orientation == orientation && (decor.width > decor.height) == landscape
        }
        compose.waitForIdle()
        compose.waitUntil(TURN_TIMEOUT_MS) { height == compose.activity.resources.configuration.screenHeightDp.dp }
        compose.waitForIdle()
        Log.i(TAG, "landscape=$landscape windowHeight=$height containerDpSize=$container")
    }

    private fun row(): Int = compose.onNodeWithTag(Bip39KeyboardTags.letter('q')).fetchSemanticsNode().size.height

    private fun assertRowsFor(where: String, windowHeight: Dp) {
        val expected = with(compose.density) { keyboardMetrics(windowHeight).row.toPx() }
        assertTrue("$where: rows ${row()} px, expected $expected px for a window $windowHeight high", abs(row() - expected) <= 1f)
    }

    @Test
    fun theKeyboardRowsFollowARealRotationBothWays() {
        try {
            compose.setContent {
                val windowHeight = windowHeight()
                val containerSize = LocalWindowInfo.current.containerDpSize
                SideEffect {
                    height = windowHeight
                    container = containerSize
                }
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
            turn(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, landscape = false)
            val activity = compose.activity
            val portraitHeight = height
            val portraitRow = row()
            assertRowsFor("portrait", portraitHeight)

            turn(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, landscape = true)
            assertSame("the activity handled the rotation itself", activity, compose.activity)
            assertTrue("landscape: a shorter window ($height, $portraitHeight in portrait)", height < portraitHeight)
            assertRowsFor("landscape", height)
            assertTrue("landscape: shorter rows (${row()} px, $portraitRow px in portrait)", row() < portraitRow)

            turn(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, landscape = false)
            assertEquals("portrait again: the portrait window", portraitHeight, height)
            assertEquals("portrait again: full rows", portraitRow, row())
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    private companion object {
        const val TAG = "WindowHeightRotation"
        const val TURN_TIMEOUT_MS = 10_000L
    }
}
