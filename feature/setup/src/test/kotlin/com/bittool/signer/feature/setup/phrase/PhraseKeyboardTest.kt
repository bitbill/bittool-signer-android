package com.bittool.signer.feature.setup.phrase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
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
import kotlin.test.assertNotNull
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

    /**
     * Back from a hardware keyboard (keyboard mode) puts the in-app keyboard away with nothing focused, as iOS ends the
     * editing (QA regression-02/03 class): a plain `clearFocus()` handed the focus to the step's first control, the
     * Cancel cross, and left a focus ring on it.
     */
    @Test
    fun backWithAHardwareKeyboardPutsTheKeyboardAwayWithNothingFocused() {
        flow()
        tapEditor()
        keyboard.assertIsDisplayed()
        compose.runOnUiThread {
            val root = checkNotNull(view.rootView.parent)
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(root, false)
        }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        keyboard.assertDoesNotExist()
        editor.assertIsNotFocused()
        compose.runOnIdle { assertNull(view.rootView.findFocus(), "no focus ring on the step's first control") }
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
            // QA regression-01: no suggestion strip either, on the plain text class (CJK input methods keep working).
            assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0, "no suggestions")
            assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, info.inputType and InputType.TYPE_MASK_VARIATION)
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

    /**
     * A physical keyboard reaches the editor directly while the in-app keyboard is up, so its shortcuts must not do what
     * the editor's menu refuses: Ctrl+C / Ctrl+Insert / Copy and Ctrl+X / Cut never put recovery words on the clipboard
     * (DECISIONS §6), and Cut deletes nothing. The Copy and Cut keys are also what a keyboard app's own Copy / Cut buttons
     * send through its connection (system keyboard mode). Paste keeps working.
     */
    @Test
    fun hardwareCopyAndCutShortcutsNeverReachTheClipboard() {
        flow()
        tapEditor()
        type(*letters("legal"), Bip39KeyboardTags.SPACE, *letters("winner"))
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("other", "zoo"))
        fun press(code: Int, meta: Int = 0) = compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
            compose.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, meta))
        }
        // Everything selected (Robolectric's key map would type Ctrl+A as "a": a real one sends no character with Ctrl).
        compose.runOnIdle { model.fields.phrase.edit { selection = TextRange(0, length) } }
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        for ((code, meta) in listOf(
            KeyEvent.KEYCODE_COPY to 0, KeyEvent.KEYCODE_CUT to 0,
            KeyEvent.KEYCODE_C to ctrl, KeyEvent.KEYCODE_X to ctrl, KeyEvent.KEYCODE_INSERT to ctrl,
        )) {
            press(code, meta)
            compose.waitForIdle()
            assertEquals("zoo", clipboard.primaryClip?.getItemAt(0)?.text?.toString(), "key $code put nothing on the clipboard")
            assertEquals("legal winner", phrase(), "key $code changed nothing")
        }
        // The Paste key still goes in, over the selection.
        press(KeyEvent.KEYCODE_PASTE)
        compose.waitUntil(5_000) { phrase() == "zoo" }
        assertNoInputConnection()
    }

    /** With the system keyboard, a keyboard app's own Copy and Cut buttons (sent through its connection) do nothing either. */
    @Test
    fun aKeyboardAppsCopyAndCutButtonsDoNothing() {
        flow()
        tapEditor()
        type(*letters("legal"))
        compose.onNodeWithTag(Bip39KeyboardTags.SYSTEM).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SwitchToSystemKeyboardDialogTags.SWITCH).performClick()
        compose.waitForIdle()
        editor.assertIsFocused()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("other", "zoo"))
        compose.runOnIdle {
            val connection = assertNotNull(view.onCreateInputConnection(EditorInfo()), "the system keyboard is connected")
            connection.setSelection(0, "legal".length)
            connection.performContextMenuAction(android.R.id.copy)
            connection.performContextMenuAction(android.R.id.cut)
        }
        compose.waitForIdle()
        assertEquals("zoo", clipboard.primaryClip?.getItemAt(0)?.text?.toString(), "nothing was copied")
        assertEquals("legal", phrase(), "nothing was cut")
    }

    /**
     * Accessibility services do not use the menu or the keys: they call the editor's own Copy and Cut actions
     * (`ACTION_COPY` / `ACTION_CUT`: TalkBack's actions menu, "copy" in Voice Access, Switch Access). With BitTool's
     * keyboard and with the system keyboard those never put recovery words on the clipboard either (ARCHITECTURE §4.4,
     * final audit A01), and Cut deletes nothing. The Paste action still goes in.
     */
    @Test
    fun accessibilityCopyAndCutActionsNeverReachTheClipboard() {
        flow()
        tapEditor()
        type(*letters("legal"), Bip39KeyboardTags.SPACE, *letters("winner"))
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        fun selectAll() = compose.runOnIdle { model.fields.phrase.edit { selection = TextRange(0, length) } }
        fun assertCopyAndCutRefused(keyboard: PhraseKeyboard) {
            assertEquals(keyboard, model.phraseKeyboard)
            clipboard.setPrimaryClip(ClipData.newPlainText("other", "zoo"))
            for (action in listOf(SemanticsActions.CopyText, SemanticsActions.CutText)) {
                selectAll()
                editor.performSemanticsAction(action)
                compose.waitForIdle()
                assertEquals("zoo", clipboard.primaryClip?.getItemAt(0)?.text?.toString(), "${action.name} put nothing on the clipboard ($keyboard)")
                assertEquals("legal winner", phrase(), "${action.name} deleted nothing ($keyboard)")
            }
        }
        assertCopyAndCutRefused(PhraseKeyboard.InApp)
        compose.onNodeWithTag(Bip39KeyboardTags.SYSTEM).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SwitchToSystemKeyboardDialogTags.SWITCH).performClick()
        compose.waitForIdle()
        editor.assertIsFocused()
        assertCopyAndCutRefused(PhraseKeyboard.System)
        // Paste reads the clipboard as before, over the selection.
        selectAll()
        editor.performSemanticsAction(SemanticsActions.PasteText)
        compose.waitUntil(5_000) { phrase() == "zoo" }
    }

    @Test
    fun onlyCopyAndCutShortcutsAreRefused() {
        fun event(code: Int, meta: Int = 0) = androidx.compose.ui.input.key.KeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        for (refused in listOf(
            event(KeyEvent.KEYCODE_C, ctrl), event(KeyEvent.KEYCODE_X, ctrl), event(KeyEvent.KEYCODE_INSERT, ctrl),
            event(KeyEvent.KEYCODE_C, meta), event(KeyEvent.KEYCODE_X, meta), event(KeyEvent.KEYCODE_COPY), event(KeyEvent.KEYCODE_CUT),
            event(KeyEvent.KEYCODE_C, ctrl or KeyEvent.META_SHIFT_ON),
        )) {
            assertTrue(isCopyOrCutShortcut(refused), "$refused is refused")
        }
        for (passed in listOf(
            event(KeyEvent.KEYCODE_C), event(KeyEvent.KEYCODE_X), event(KeyEvent.KEYCODE_V, ctrl), event(KeyEvent.KEYCODE_A, ctrl),
            event(KeyEvent.KEYCODE_PASTE), event(KeyEvent.KEYCODE_INSERT, KeyEvent.META_SHIFT_ON), event(KeyEvent.KEYCODE_DEL),
            event(KeyEvent.KEYCODE_SPACE), event(KeyEvent.KEYCODE_ENTER),
        )) {
            assertFalse(isCopyOrCutShortcut(passed), "$passed passes")
        }
    }

    /**
     * Focus moving from a field typed with the system keyboard (the wallet name) to the editor: the system keyboard goes
     * away and the in-app keyboard comes up, with no input connection; back to the name, the system keyboard returns.
     */
    @Test
    fun theSystemKeyboardOfAnotherFieldGoesAwayWhenTheEditorIsFocused() {
        flow()
        compose.onNodeWithTag("setup.name").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(view.onCheckIsTextEditor(), "the name is typed with the system keyboard")
            assertTrue(shadowOf(imm()).isSoftInputVisible, "the name field asked for the system keyboard")
        }
        keyboard.assertDoesNotExist()
        editor.performScrollTo()
        tapEditor()
        keyboard.assertIsDisplayed()
        editor.assertIsFocused()
        assertNoInputConnection()
        compose.runOnIdle { assertFalse(shadowOf(imm()).isSoftInputVisible, "the system keyboard was put away") }
        compose.onNodeWithTag("setup.name").performScrollTo().performClick()
        compose.waitForIdle()
        keyboard.assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(view.onCheckIsTextEditor())
            assertTrue(shadowOf(imm()).isSoftInputVisible, "the name field gets the system keyboard again")
        }
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

    /**
     * Only "Continue" steps aside on a short window: an error (which typing does not clear, iOS) stays above the
     * keyboard while the person fixes the phrase it is about, and Continue comes back under it with Done.
     */
    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun onAShortWindowAnErrorStaysAboveTheKeyboardWhileContinueStepsAside() {
        flow()
        compose.runOnIdle { model.fields.phrase.setTextAndPlaceCursorAtEnd("abandon abandon abandon") }
        compose.onNodeWithTag("setup.import").performClick()
        compose.waitForIdle()
        val error = compose.onNodeWithTag("setup.error")
        error.assert(hasText("Invalid recovery phrase", substring = true)).assertIsDisplayed()
        editor.performScrollTo()
        tapEditor()
        keyboard.assertIsDisplayed()
        compose.onNodeWithTag("setup.import").assertDoesNotExist()
        error.assertIsDisplayed()
        val errorBottom = error.fetchSemanticsNode().boundsInRoot.bottom
        val keyboardTop = keyboard.fetchSemanticsNode().boundsInRoot.top
        assertTrue(errorBottom <= keyboardTop + 1f, "the error ends at $errorBottom, under the keyboard at $keyboardTop")
        compose.onNodeWithTag(Bip39KeyboardTags.DONE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("setup.import").assertIsDisplayed()
        error.assertIsDisplayed()
    }
}
