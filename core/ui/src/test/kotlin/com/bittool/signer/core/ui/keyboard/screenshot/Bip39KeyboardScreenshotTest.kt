package com.bittool.signer.core.ui.keyboard.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.ui.keyboard.Bip39Keyboard
import com.bittool.signer.core.ui.keyboard.InAppKeyboardChip
import com.bittool.signer.core.ui.keyboard.KeyboardSuggestions
import com.bittool.signer.core.ui.screenshot.InLook
import com.bittool.signer.core.ui.screenshot.Look
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the in-app recovery-phrase keyboard on its own (completions, a hint, the "BitTool keyboard" chip) in light,
 * dark, font scale 2.0 and Arabic RTL to `core/ui/build/outputs/roborazzi/keyboard/` (record only;
 * `-Psigner.screenshots=false` skips it).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xhdpi")
class Bip39KeyboardScreenshotTest(private val look: Look) {
    @get:Rule
    val compose = createComposeRule()

    private fun record(name: String, suggestions: KeyboardSuggestions) {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
        compose.setContent {
            InLook(look) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    InAppKeyboardChip(onClick = {}, modifier = Modifier.padding(horizontal = 16.dp))
                    Bip39Keyboard(remember { TextFieldState() }, suggestions, onSuggestion = {}, onSwitchToSystemKeyboard = {}, onDone = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/keyboard/$name-${look.name.lowercase()}.png")
    }

    @Test
    fun completions() = record("01-completions", KeyboardSuggestions.Words(listOf("abandon", "ability", "able", "about", "above")))

    @Test
    fun hint() = record("02-hint", KeyboardSuggestions.Hint("Type a word to see suggestions"))

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = Look.entries.map { arrayOf<Any>(it) }
    }
}
