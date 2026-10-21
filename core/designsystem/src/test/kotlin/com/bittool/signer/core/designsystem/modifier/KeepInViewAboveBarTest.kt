package com.bittool.signer.core.designsystem.modifier

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA setup-02: on a page with a bottom bar that rises with the keyboard, the focused field and the line being typed
 * must end up above the bar and its 12 dp edge fade (iOS `WalletSetupFlow.swift:107-119`, `light-05-import-phrase.png`).
 * A 600 dp window with the bar drawn over its bottom, in the two layouts the modifier serves:
 *
 * - under the bar: the scroll covers the whole window and the bar's height is bottom padding inside the scroll, so the
 *   content scrolls beneath the bar;
 * - above the bar (the setup pages, `SetupScaffold`): the bar's height is padding outside the scroll, so the viewport
 *   ends at the bar's top edge and shrinks as the bar grows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class KeepInViewAboveBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val scroll = ScrollState(0)
    private val focus = FocusRequester()
    private val text = TextFieldState()
    private var bar by mutableStateOf(48.dp)

    /**
     * [above]: the field's top in the column before scrolling; [growing]: a multi-line field as tall as its text;
     * [underBar]: whether the content scrolls beneath the bar (`scrollsUnderBar`).
     */
    private fun page(above: Dp = 440.dp, growing: Boolean = false, underBar: Boolean = true) {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Box(Modifier.size(width = 360.dp, height = 600.dp)) {
                    val column = if (underBar) {
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .keepInViewAboveBar(bar, scrollsUnderBar = true)
                            .padding(bottom = bar)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = bar)
                            .verticalScroll(scroll)
                            .keepInViewAboveBar(bar, scrollsUnderBar = false)
                    }
                    Column(column) {
                        Spacer(Modifier.height(above))
                        val size = if (growing) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().height(56.dp)
                        BasicTextField(text, size.focusRequester(focus).testTag("field"))
                        Spacer(Modifier.height(300.dp))
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bar).testTag("bar"))
                }
            }
        }
    }

    private val fade get() = 12f * compose.density.density

    private fun barTop() = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.top

    /** The field ends above the bar and its edge fade, and is still on screen. */
    private fun assertFieldAboveTheBar(what: String) {
        compose.waitForIdle()
        val field = compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        val barTop = barTop()
        assertTrue(field.bottom <= barTop - fade + 1f, "$what: the field (${field.top}–${field.bottom}) ends above the bar's fade at $barTop")
        assertTrue(field.top >= 0f, "$what: the field is still on screen (${field.top})")
    }

    /** The bottom of the last line of the field (where the caret is after typing), in the window. */
    private fun lastLineBottom(expectedLines: Int): Float {
        val node = compose.onNodeWithTag("field").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(expectedLines, layout.lineCount)
        return node.positionInRoot.y + layout.getLineBottom(layout.lineCount - 1)
    }

    private fun openKeyboard() {
        // The bar grows to 300 dp, in steps like the inset animation.
        for (height in listOf(120.dp, 220.dp, 300.dp)) {
            bar = height
            compose.waitForIdle()
        }
    }

    @Test
    fun aFocusedFieldMovesBackAboveTheBarWhenTheKeyboardRaisesIt() {
        page()
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        // Above the 48 dp bar and its fade: nothing to do.
        assertEquals(0, scroll.value)
        openKeyboard()
        assertFieldAboveTheBar("after the keyboard opened")
    }

    @Test
    fun focusingAFieldUnderTheBarScrollsItAboveTheBarNotJustIntoTheWindow() {
        bar = 300.dp
        page()
        compose.runOnIdle { focus.requestFocus() }
        assertFieldAboveTheBar("after focusing")
    }

    /** The bar draws a 12 dp fade over the content above its top edge: a field ending inside it is moved clear of it. */
    @Test
    fun aFocusedFieldClearsTheBarsEdgeFade() {
        // The field ends 6 dp above the 48 dp bar, inside its fade.
        page(above = (600 - 48 - 6 - 56).dp)
        compose.runOnIdle { focus.requestFocus() }
        assertFieldAboveTheBar("after focusing")
        assertTrue(scroll.value > 0, "the field was moved")
    }

    @Test
    fun theLineBeingTypedStaysAboveTheBar() {
        bar = 300.dp
        page(above = 150.dp, growing = true)
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        // Ten lines: the field grows past the bar's top and the caret, on the last line, would end up under the bar.
        compose.onNodeWithTag("field").performTextInput((1..10).joinToString("\n") { "word $it" })
        compose.waitForIdle()
        val caretBottom = lastLineBottom(10)
        assertTrue(caretBottom <= barTop() + 1f, "the line being typed (bottom $caretBottom) is above the bar at ${barTop()}")
    }

    @Test
    fun withoutFocusTheBarGrowingMovesNothing() {
        page()
        bar = 300.dp
        compose.waitForIdle()
        assertEquals(0, scroll.value, "no focused field, no scrolling")
    }

    /** The setup pages' layout: the viewport ends at the bar, which must not be counted a second time. */
    @Test
    fun aboveTheBarAFocusedFieldIsScrolledJustClearOfTheBar() {
        bar = 300.dp
        page(underBar = false)
        compose.runOnIdle { focus.requestFocus() }
        assertFieldAboveTheBar("after focusing")
        val field = compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        // Just above the fade, not a bar's height higher (which would push it towards the top of a short viewport).
        assertTrue(field.bottom >= barTop() - fade - 2f * compose.density.density, "the field ends at ${field.bottom}, the bar is at ${barTop()}")
    }

    @Test
    fun aboveTheBarAFocusedFieldFollowsTheKeyboard() {
        page(underBar = false)
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        assertEquals(0, scroll.value)
        openKeyboard()
        assertFieldAboveTheBar("after the keyboard opened")
    }

    /**
     * A tall editor being typed in when the keyboard opens: the viewport shrinks under the line with the caret, which
     * must end up above the bar's fade (Compose itself at most lines the editor's box up with the viewport's edge).
     */
    @Test
    fun aboveTheBarTheLineBeingTypedFollowsTheKeyboard() {
        page(above = 150.dp, growing = true, underBar = false)
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag("field").performTextInput((1..10).joinToString("\n") { "word $it" })
        compose.waitForIdle()
        assertEquals(0, scroll.value, "precondition: the editor fits above the 48 dp bar")
        openKeyboard()
        val caretBottom = lastLineBottom(10)
        assertTrue(caretBottom <= barTop() - fade + 1f, "the line being typed (bottom $caretBottom) clears the bar at ${barTop()}")
        assertTrue(caretBottom > 0f, "the line being typed is on screen")
    }
}
