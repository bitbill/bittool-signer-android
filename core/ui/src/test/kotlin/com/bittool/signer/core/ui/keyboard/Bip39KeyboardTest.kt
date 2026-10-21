package com.bittool.signer.core.ui.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The in-app keyboard over a real Compose text field (Robolectric, the real input stack): it types at the caret, deletes
 * and repeats, pastes the clipboard text unchanged, hands suggestions, Done and the globe key to its caller, never takes
 * the focus, and the field it types into has no input-method connection while hardware keys still reach it. TalkBack
 * finds a named, activatable button for every key; right to left keeps the QWERTY order; labels stop growing at the cap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h852dp-xhdpi")
class Bip39KeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = TextFieldState()
    private var suggestions: KeyboardSuggestions by mutableStateOf(KeyboardSuggestions.None)
    private var inAppKeyboard by mutableStateOf(true)
    private var fontScale by mutableStateOf(1f)
    private val picked = mutableListOf<String>()
    private var done = 0
    private var switches = 0
    private lateinit var view: View

    private fun show(rtl: Boolean = false) {
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) { Host() }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Host() {
        Column(Modifier.fillMaxSize()) {
            InAppKeyboardInput(inAppKeyboard) {
                BasicTextField(state, Modifier.fillMaxWidth().testTag(FIELD))
            }
            Spacer(Modifier.weight(1f))
            Bip39Keyboard(
                state = state,
                suggestions = suggestions,
                onSuggestion = { picked += it },
                onSwitchToSystemKeyboard = { switches++ },
                onDone = { done++ },
            )
        }
    }

    private fun key(tag: String) = compose.onNodeWithTag(tag)

    private fun type(vararg tags: String) {
        tags.forEach { key(it).performClick() }
        compose.waitForIdle()
    }

    private fun letter(c: Char) = Bip39KeyboardTags.letter(c)

    private fun composeText(): String = compose.runOnIdle { state.text.toString() }

    @Test
    fun lettersSpaceAndBackspaceTypeAtTheCaret() {
        show()
        type(letter('a'), letter('b'), Bip39KeyboardTags.SPACE, letter('z'), letter('o'), letter('o'), Bip39KeyboardTags.BACKSPACE)
        assertEquals("ab zo", composeText())
        // In the middle, over a selection, like any keyboard.
        compose.runOnIdle { state.edit { selection = TextRange(1, 2) } }
        type(letter('x'))
        compose.runOnIdle {
            assertEquals("ax zo", state.text.toString())
            assertEquals(TextRange(2), state.selection)
        }
    }

    @Test
    fun backspaceDeletesOnTouchDownAndRepeatsWhileHeld() {
        show()
        compose.runOnIdle { state.edit { replace(0, length, "abandon ability able"); placeCursorAtEnd() } }
        compose.mainClock.autoAdvance = false
        key(Bip39KeyboardTags.BACKSPACE).performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("abandon ability abl", composeText(), "deleted on touch-down")
        compose.mainClock.advanceTimeBy(1_000)
        key(Bip39KeyboardTags.BACKSPACE).performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        val left = composeText()
        assertTrue(left.length < "abandon ability abl".length - 5, "repeats while held ($left.length left)")
        assertTrue("abandon ability able".startsWith(left))
    }

    @Test
    fun pasteInsertsTheClipboardTextUnchangedAtTheCaret() {
        show()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("phrase", "legal winner\nthank"))
        compose.runOnIdle { state.edit { replace(0, length, "zoo "); placeCursorAtEnd() } }
        type(Bip39KeyboardTags.PASTE)
        compose.waitUntil(5_000) { state.text.length > 4 }
        assertEquals("zoo legal winner\nthank", composeText())
        // The keyboard only reads the clipboard.
        assertEquals("legal winner\nthank", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun suggestionsHintsDoneAndTheGlobeKeyGoToTheCaller() {
        show()
        suggestions = KeyboardSuggestions.Words(listOf("abandon", "ability"))
        compose.onNodeWithContentDescription("Insert abandon").assertExists()
        key("suggestion.ability").performClick()
        suggestions = KeyboardSuggestions.Hint("Type a word to see suggestions")
        compose.onNodeWithText("Type a word to see suggestions").assertExists()
        key(Bip39KeyboardTags.DONE).performClick()
        key(Bip39KeyboardTags.SYSTEM).performClick()
        compose.runOnIdle {
            assertEquals(listOf("ability"), picked)
            assertEquals(1, done)
            assertEquals(1, switches)
            assertEquals("", state.text.toString(), "a suggestion is the caller's edit")
        }
        compose.onNodeWithText("System keyboard").assertExists()
    }

    @Test
    fun theFieldHasNoInputMethodConnectionAndKeysNeverTakeItsFocus() {
        show()
        key(FIELD).requestFocus()
        type(letter('q'), Bip39KeyboardTags.SPACE, Bip39KeyboardTags.BACKSPACE, Bip39KeyboardTags.PASTE)
        key(FIELD).assertIsFocused()
        compose.runOnIdle {
            assertFalse(view.onCheckIsTextEditor(), "the focused field is not an input-method editor")
            assertNull(view.onCreateInputConnection(EditorInfo()), "no InputConnection exists")
            val imm = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            assertFalse(shadowOf(imm).isSoftInputVisible, "no soft keyboard was asked for")
        }
        // The system keyboard mode connects, without personalised learning.
        inAppKeyboard = false
        compose.waitForIdle()
        key(FIELD).requestFocus()
        compose.runOnIdle {
            assertTrue(view.onCheckIsTextEditor())
            val info = EditorInfo()
            view.onCreateInputConnection(info)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
    }

    /** A physical keyboard keeps working without any input-method connection: letters, space, backspace, enter. */
    @Test
    fun hardwareKeysStillReachTheField() {
        show()
        key(FIELD).requestFocus()
        compose.waitForIdle()
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_Z)) {
            compose.runOnUiThread {
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            compose.waitForIdle()
        }
        assertEquals("ab \nz", composeText())
        compose.runOnIdle { assertNull(view.onCreateInputConnection(EditorInfo())) }
    }

    @Test
    fun everyKeyIsANamedButtonTalkBackCanActivate() {
        show()
        suggestions = KeyboardSuggestions.Words(listOf("abandon"))
        compose.waitForIdle()
        val keys = compose.onAllNodesWithTag(Bip39KeyboardTags.KEYBOARD).fetchSemanticsNodes().single().let { keyboard ->
            fun all(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                node.children.flatMap { listOf(it) + all(it) }
            all(keyboard).filter { it.config.getOrNull(SemanticsProperties.TestTag)?.let { tag -> tag.startsWith("keyboard.") || tag.startsWith("suggestion.") } == true }
        }
        assertEquals(26 + 5 + 1, keys.size, "26 letters, paste, backspace, system, space, Done and one suggestion")
        for (node in keys) {
            val tag = node.config[SemanticsProperties.TestTag]
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            assertTrue(label.isNotBlank(), "$tag has a name")
            assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role), "$tag is a button")
            assertTrue(SemanticsActions.OnClick in node.config, "$tag can be activated")
            assertFalse(node.config.getOrNull(SemanticsProperties.Focused) == true)
        }
        // A TalkBack double tap is the semantics click.
        key(letter('q')).performSemanticsAction(SemanticsActions.OnClick)
        key(Bip39KeyboardTags.BACKSPACE).performSemanticsAction(SemanticsActions.OnClick)
        key(letter('w')).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("w", composeText())
        compose.onNode(hasTestTag(Bip39KeyboardTags.BACKSPACE) and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Delete"))).assertExists()
        compose.onNode(hasTestTag(Bip39KeyboardTags.PASTE) and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Paste from Clipboard"))).assertExists()
        compose.onNode(hasTestTag(Bip39KeyboardTags.SPACE) and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Space"))).assertExists()
    }

    @Test
    fun aKeyPressPlaysTheKeyboardTapHaptic() {
        show()
        type(letter('e'))
        compose.runOnIdle { assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(view).lastHapticFeedbackPerformed()) }
    }

    @Test
    fun whileShownTheWindowIsAccessibilitySensitive() {
        show()
        compose.runOnIdle { assertTrue(view.isAccessibilityDataSensitive, "only accessibility tools see the keys being pressed") }
    }

    @Test
    fun rightToLeftKeepsTheQwertyOrder() {
        show(rtl = true)
        val q = key(letter('q')).fetchSemanticsNode().boundsInRoot
        val p = key(letter('p')).fetchSemanticsNode().boundsInRoot
        val paste = key(Bip39KeyboardTags.PASTE).fetchSemanticsNode().boundsInRoot
        val backspace = key(Bip39KeyboardTags.BACKSPACE).fetchSemanticsNode().boundsInRoot
        assertTrue(q.left < p.left, "q stays at the left edge")
        assertTrue(paste.left < backspace.left, "paste left, backspace right")
    }

    /** Font scale 2.0: the rows keep their height and the letters grow to the cap only. */
    @Test
    fun labelsStopGrowingAtTheCap() {
        fontScale = 2f
        show()
        val keyboardHeight = key(Bip39KeyboardTags.KEYBOARD).fetchSemanticsNode().size.height
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("a") and hasAnyAncestor(hasTestTag(letter('a'))), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val drawn = layouts.single().let { with(it.layoutInput.density) { it.layoutInput.style.fontSize.toPx() } }
        val cap = with(compose.density) { (22 * Bip39KeyboardDefaults.LABEL_FONT_SCALE_CAP).dp.toPx() }
        assertTrue(drawn <= cap + 0.5f, "letters are drawn at $drawn px, the cap is $cap px")
        assertTrue(drawn > with(compose.density) { 22.dp.toPx() }, "but they do grow with the font size")
        // Same keyboard at the default size.
        fontScale = 1f
        compose.waitForIdle()
        assertEquals(keyboardHeight, key(Bip39KeyboardTags.KEYBOARD).fetchSemanticsNode().size.height, "the rows keep their height")
    }

    /** Landscape: shorter rows, so the page keeps room for the editor. */
    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun shortWindowsGetShorterRows() {
        show()
        val landscape = key(letter('q')).fetchSemanticsNode().size.height
        val portrait = with(compose.density) { keyboardMetrics(852.dp).row.roundToPx() }
        assertTrue(landscape < portrait, "rows are $landscape px in landscape, $portrait px in portrait")
        assertTrue(landscape >= with(compose.density) { keyboardMetrics(0.dp).row.roundToPx() }, "never below the compact rows")
    }

    private companion object {
        const val FIELD = "field"
    }
}
