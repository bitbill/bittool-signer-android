package com.bittool.signer.feature.setup.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.keyboard.Bip39KeyboardTags
import com.bittool.signer.core.ui.keyboard.PhraseKeyboard
import com.bittool.signer.feature.setup.Look
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.applySnapshots
import com.bittool.signer.feature.setup.flow.SetupFlow
import com.bittool.signer.feature.setup.flow.WalletSetupViewModel
import com.bittool.signer.feature.setup.setKeyboardHeight
import com.bittool.signer.feature.setup.testBip39
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the import step with BitTool's own recovery-phrase keyboard to
 * `feature/setup/build/outputs/roborazzi/keyboard/` for visual QA (record only; `-Psigner.screenshots=false` skips it):
 * the keyboard with its hint and with the editor's completions in light, dark, font scale 2.0 and Arabic (the keys stay
 * QWERTY, the hint follows the interface), a held key with its preview, the warning before the system keyboard, the
 * system keyboard's bar with the "BitTool keyboard" chip, and phones in landscape (shorter rows, Continue stepped aside).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h880dp-xhdpi")
class PhraseKeyboardScreenshotTest(private val screen: Screen, private val look: Look) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val factory = FakeRecoverySessionFactory()

    enum class Screen(val slug: String, val everyLook: Boolean = false) {
        /** Just focused: the hint "Type a word to see suggestions" in the top row. */
        Empty("01-keyboard-empty", everyLook = true),

        /** A word being typed: the editor's completions as chips. */
        Typing("02-keyboard-typing", everyLook = true),

        /** A letter held down: its preview over the row above. */
        KeyHeld("03-keyboard-key-held"),

        /** The globe key: the warning before the system keyboard. */
        SwitchWarning("04-keyboard-switch-warning"),

        /** The system keyboard chosen: its bar starts with the "BitTool keyboard" chip. */
        SystemKeyboard("05-system-keyboard-switch-back"),
    }

    @Test
    fun capture() {
        if (look.rtl) RuntimeEnvironment.setQualifiers("+ar")
        val name = "build/outputs/roborazzi/keyboard/${screen.slug}-${look.name.lowercase()}.png"
        recordKeyboard(compose, factory, look, screen, name)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = Screen.entries.flatMap { screen ->
            val looks = if (screen.everyLook) Look.entries else listOf(Look.Light, Look.Dark)
            looks.map { arrayOf<Any>(screen, it) }
        }
    }
}

/** The same keyboard on a phone in landscape (852 × 393 dp): shorter rows, and Continue steps aside while it is up. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w852dp-h393dp-xhdpi")
class PhraseKeyboardLandscapeScreenshotTest(private val look: Look) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        recordKeyboard(
            compose, FakeRecoverySessionFactory(), look, PhraseKeyboardScreenshotTest.Screen.Typing,
            "build/outputs/roborazzi/keyboard/06-keyboard-landscape-${look.name.lowercase()}.png",
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = listOf(Look.Light, Look.Dark).map { arrayOf<Any>(it) }
    }
}

private fun recordKeyboard(
    compose: AndroidComposeTestRule<*, ComponentActivity>,
    factory: FakeRecoverySessionFactory,
    look: Look,
    screen: PhraseKeyboardScreenshotTest.Screen,
    path: String,
) {
    val model = WalletSetupViewModel(SetupIntent.Restore, FakeSignerStore(), testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
    if (screen == PhraseKeyboardScreenshotTest.Screen.SystemKeyboard) model.usePhraseKeyboard(PhraseKeyboard.System)
    compose.setContent { SetupTestHost(factory, look) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
    compose.waitForIdle()
    compose.onNodeWithTag("recovery.input.visible").performClick()
    compose.waitForIdle()
    fun act(block: () -> Unit) {
        compose.runOnUiThread {
            block()
            applySnapshots()
        }
        compose.waitForIdle()
    }
    when (screen) {
        PhraseKeyboardScreenshotTest.Screen.Empty -> Unit
        PhraseKeyboardScreenshotTest.Screen.Typing, PhraseKeyboardScreenshotTest.Screen.KeyHeld ->
            act { model.fields.phrase.setTextAndPlaceCursorAtEnd("abandon ability ab") }
        PhraseKeyboardScreenshotTest.Screen.SwitchWarning -> {
            act { model.fields.phrase.setTextAndPlaceCursorAtEnd("abandon ability ") }
            compose.onNodeWithTag(Bip39KeyboardTags.SYSTEM).performClick()
        }
        PhraseKeyboardScreenshotTest.Screen.SystemKeyboard -> {
            act { model.fields.phrase.setTextAndPlaceCursorAtEnd("abandon ability ab") }
            // A 300 dp system keyboard, as the window reports it.
            compose.runOnUiThread { compose.activity.setKeyboardHeight((300 * compose.density.density).toInt()) }
        }
    }
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(1_000)
    if (screen == PhraseKeyboardScreenshotTest.Screen.KeyHeld) {
        compose.onNodeWithTag(Bip39KeyboardTags.letter('g')).performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
    }
    if (screen == PhraseKeyboardScreenshotTest.Screen.SwitchWarning) captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
}
