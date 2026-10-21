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
 * QA setup-02: on a page whose content scrolls beneath a bottom bar that rises with the keyboard, the focused field and
 * the line being typed must end up above the bar (iOS `WalletSetupFlow.swift:107-119`, `light-05-import-phrase.png`).
 * The page here is the setup layout: a 600 dp window, a scrolling column with the bar's height as bottom padding inside
 * the scroll, and the bar drawn over the column's bottom.
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

    /** [above]: the field's top in the column before scrolling; [growing]: a multi-line field as tall as its text. */
    private fun page(above: Dp = 440.dp, growing: Boolean = false) {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Box(Modifier.size(width = 360.dp, height = 600.dp)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .keepInViewAboveBar(bar)
                            .padding(bottom = bar),
                    ) {
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

    private fun assertFieldAboveTheBar(what: String) {
        compose.waitForIdle()
        val field = compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        val barTop = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.top
        assertTrue(field.bottom <= barTop + 1f, "$what: the field (${field.top}–${field.bottom}) ends above the bar at $barTop")
        assertTrue(field.top >= 0f, "$what: the field is still on screen (${field.top})")
    }

    @Test
    fun aFocusedFieldMovesBackAboveTheBarWhenTheKeyboardRaisesIt() {
        page()
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        // Above the 48 dp bar: nothing to do.
        assertEquals(0, scroll.value)
        // The keyboard opens: the bar grows to 300 dp, in steps like the inset animation.
        for (height in listOf(120.dp, 220.dp, 300.dp)) {
            bar = height
            compose.waitForIdle()
        }
        assertFieldAboveTheBar("after the keyboard opened")
    }

    @Test
    fun focusingAFieldUnderTheBarScrollsItAboveTheBarNotJustIntoTheWindow() {
        bar = 300.dp
        page()
        compose.runOnIdle { focus.requestFocus() }
        assertFieldAboveTheBar("after focusing")
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
        val node = compose.onNodeWithTag("field").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        val caretBottom = node.positionInRoot.y + layout.getLineBottom(layout.lineCount - 1)
        val barTop = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.top
        assertEquals(10, layout.lineCount)
        assertTrue(caretBottom <= barTop + 1f, "the line being typed (bottom $caretBottom) is above the bar at $barTop")
    }

    @Test
    fun withoutFocusTheBarGrowingMovesNothing() {
        page()
        bar = 300.dp
        compose.waitForIdle()
        assertEquals(0, scroll.value, "no focused field, no scrolling")
    }
}
