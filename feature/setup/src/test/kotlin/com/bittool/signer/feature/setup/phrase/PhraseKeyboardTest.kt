package com.bittool.signer.feature.setup.phrase

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.keyboard.Bip39KeyboardTags
import com.bittool.signer.core.ui.keyboard.PhraseKeyboard
import com.bittool.signer.core.ui.keyboard.SwitchToSystemKeyboardDialogTags
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.setKeyboardHeight
import com.bittool.signer.feature.setup.testBip39
import com.bittool.signer.feature.setup.flow.SetupFlow
import com.bittool.signer.feature.setup.flow.WalletSetupViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The import step with BitTool's own keyboard (Robolectric, the real Compose input stack and the real setup flow): the
 * editor opens the in-app keyboard and has **no input-method connection**; keys, the editor's suggestions and a
 * hardware keyboard type the phrase; Done and Back put the keyboard away first; the globe key asks before the system
 * keyboard (which then connects, incognito) and the "BitTool keyboard" chip above the system keyboard switches back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h852dp-xhdpi")
class PhraseKeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore()
    private val factory = FakeRecoverySessionFactory()
    private lateinit var view: View
    private lateinit var model: WalletSetupViewModel

    private fun flow() {
        model = WalletSetupViewModel(SetupIntent.Restore, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent {
            view = LocalView.current
            SetupTestHost(factory) { SetupFlow(model, onFinished = {}, onCancelled = {}) }
        }
        compose.waitForIdle()
    }

    private val editor get() = compose.onNodeWithTag(EDITOR_TAG)
    private val keyboard get() = compose.onNodeWithTag(Bip39KeyboardTags.KEYBOARD)

    private fun tapEditor() {
        editor.performClick()
        compose.waitForIdle()
    }

    private fun type(vararg tags: String) {
        tags.forEach { compose.onNodeWithTag(it).performClick() }
        compose.waitForIdle()
    }

    private fun letters(word: String) = word.map { Bip39KeyboardTags.letter(it) }.toTypedArray()

    private fun phrase(): String = compose.runOnIdle { model.fields.phrase.text.toString() }

    private fun imm() = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun assertNoInputConnection() = compose.runOnIdle {
        assertFalse(view.onCheckIsTextEditor(), "the focused editor is not an input-method editor")
        assertNull(view.onCreateInputConnection(EditorInfo()), "no InputConnection exists while the in-app keyboard is up")
    }

    @Test
    fun theEditorOpensTheInAppKeyboardAndHasNoInputConnection() {
        flow()
        keyboard.assertDoesNotExist()
        tapEditor()
        keyboard.assertIsDisplayed()
        editor.assertIsFocused()
        assertNoInputConnection()
        compose.runOnIdle { assertFalse(shadowOf(imm()).isSoftInputVisible, "the system keyboard was never asked for") }
        // The system keyboard's bar is not shown; Continue stays above the keyboard.
        compose.onNodeWithTag("recovery.keyboardDone").assertDoesNotExist()
        compose.onNodeWithTag("setup.import").assertIsDisplayed()
        assertEquals(PhraseKeyboard.InApp, model.phraseKeyboard)
    }

    @Test
    fun keysTheEditorsSuggestionsAndAHardwareKeyboardTypeThePhrase() {
        flow()
        tapEditor()
        type(*letters("ab"))
        assertEquals("ab", phrase())
        // The keyboard's top row shows the editor's own completions; a chip is its "Insert" action.
        compose.onNodeWithTag("suggestion.abandon").performClick()
        compose.waitForIdle()
        assertEquals("abandon ", phrase())
        compose.onNodeWithText("Type a word to see suggestions").assertExists()
        type(*letters("zoo"), Bip39KeyboardTags.SPACE, *letters("x"), Bip39KeyboardTags.BACKSPACE)
        assertEquals("abandon zoo ", phrase())
        // A physical keyboard: letters, space, backspace, enter, still without an input connection.
        for (code in listOf(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER)) {
            compose.runOnUiThread {
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            compose.waitForIdle()
        }
        assertEquals("abandon zoo leg \n", phrase())
        editor.assertIsFocused()
        assertNoInputConnection()
    }

    @Test
    fun doneAndBackPutTheKeyboardAwayBeforeAnythingElse() {
        flow()
        tapEditor()
        type(*letters("legal"))
        compose.onNodeWithTag(Bip39KeyboardTags.DONE).performClick()
        compose.waitForIdle()
        keyboard.assertDoesNotExist()
        editor.assertIsNotFocused()
        assertEquals("legal", phrase(), "Done keeps the words")
        // Tapping the editor brings the in-app keyboard back, never the system one.
        tapEditor()
        keyboard.assertIsDisplayed()
        assertNoInputConnection()
        // Back closes the keyboard first; only the next Back asks to discard the draft.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        keyboard.assertDoesNotExist()
        compose.onNodeWithTag("setup.discard").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("setup.discard").assertExists()
        assertEquals("legal", phrase())
    }

    /** The predictive back gesture: a cancelled one leaves the keyboard up, a committed one puts it away, nothing more. */
    @Test
    fun thePredictiveBackGestureClosesTheKeyboardOnlyWhenCommitted() {
        flow()
        tapEditor()
        type(*letters("ab"))
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(8f, 600f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 600f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        keyboard.assertIsDisplayed()
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        keyboard.assertIsDisplayed()
        editor.assertIsFocused()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(8f, 600f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(240f, 600f, 0.8f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        keyboard.assertDoesNotExist()
        compose.onNodeWithTag("setup.discard").assertDoesNotExist()
        assertEquals("ab", phrase())
    }

    @Test
    fun theGlobeKeyWarnsBeforeTheSystemKeyboardAndTheChipSwitchesBack() {
        flow()
        tapEditor()
        type(*letters("ab"))
        // Warning first; "Keep BitTool keyboard" (and Back) stay.
        compose.onNodeWithTag(Bip39KeyboardTags.SYSTEM).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Switch to the system keyboard?").assertExists()
        compose.onNodeWithText("Switch only if you need to type a Chinese, Japanese or Korean recovery phrase.", substring = true).assertExists()
        compose.onNodeWithTag(SwitchToSystemKeyboardDialogTags.KEEP).performClick()
        compose.waitForIdle()
        keyboard.assertIsDisplayed()
        editor.assertIsFocused()
        assertEquals(PhraseKeyboard.InApp, model.phraseKeyboard)

        compose.onNodeWithTag(Bip39KeyboardTags.SYSTEM).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SwitchToSystemKeyboardDialogTags.SWITCH).performClick()
        compose.waitForIdle()
        keyboard.assertDoesNotExist()
        editor.assertIsFocused()
        assertEquals(PhraseKeyboard.System, model.phraseKeyboard)
        assertEquals("ab", phrase(), "the words stay")
        compose.runOnIdle {
            assertTrue(view.onCheckIsTextEditor(), "the system keyboard mode talks to the input method")
            val info = EditorInfo()
            view.onCreateInputConnection(info)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0, "incognito")
            assertTrue(shadowOf(imm()).isSoftInputVisible, "the system keyboard was asked for")
        }

        // Above the system keyboard: the suggestion bar, starting with "BitTool keyboard".
        compose.runOnUiThread { compose.activity.setKeyboardHeight((300 * compose.density.density).toInt()) }
        compose.waitForIdle()
        compose.onNodeWithTag("recovery.keyboardDone").assertIsDisplayed()
        compose.onNodeWithTag(Bip39KeyboardTags.SWITCH_BACK).performClick()
        compose.runOnUiThread { compose.activity.setKeyboardHeight(0) }
        compose.waitForIdle()
        keyboard.assertIsDisplayed()
        editor.assertIsFocused()
        assertEquals(PhraseKeyboard.InApp, model.phraseKeyboard)
        assertNoInputConnection()
        compose.runOnIdle { assertFalse(shadowOf(imm()).isSoftInputVisible, "the system keyboard was put away") }
        type(*letters("a"))
        assertEquals("aba", phrase())
    }

    /** Phones in landscape: "Continue" steps aside while the keyboard is up, so the editor keeps room; Done brings it back. */
    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun onAShortWindowContinueStepsAsideWhileTheKeyboardIsUp() {
        flow()
        compose.onNodeWithTag("setup.import").assertExists()
        editor.performScrollTo()
        tapEditor()
        keyboard.assertIsDisplayed()
        compose.onNodeWithTag("setup.import").assertDoesNotExist()
        compose.onNodeWithTag(Bip39KeyboardTags.DONE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("setup.import").assertExists()
    }
}
