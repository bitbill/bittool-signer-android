package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * `ValueRow` and `NavRow(value = …)` (iOS `LabeledContent` and a menu `Picker` row in a `Form`, backlog B6): the value
 * sits against the trailing edge (the chevron), whatever the label's length.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text metrics
class LabelValueRowsTest {
    @get:Rule
    val compose = createComposeRule()

    /** [body] in a 320 dp wide column (the Robolectric window); the box tagged "row" spans the whole width. */
    private fun content(rtl: Boolean = false, body: @Composable () -> Unit) {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Column(Modifier.width(320.dp)) { Box(Modifier.testTag("row")) { body() } }
                }
            }
        }
    }

    private fun px(dp: Int): Float = with(compose.density) { dp.dp.toPx() }

    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun row() = compose.onNodeWithTag("row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1.5f, "$what: expected $expected, was $actual")

    @Test
    fun aShortValueEndsAtTheTrailingEdgeNotMidRow() {
        content { ValueRow("Version", "1.0.0 (1)") }
        assertNear(row().right - px(16), bounds("1.0.0 (1)").right, "value end")
        assertNear(row().left + px(16), bounds("Version").left, "label start")
    }

    @Test
    fun aNavRowValueEndsAtItsChevron() {
        content { NavRow("Language", SignerIcons.Language, onClick = {}, value = "English") }
        // 16 dp padding, the 20 dp chevron and the 16 dp gap before it.
        assertNear(row().right - px(16 + 20 + 16), bounds("English").right, "value end")
    }

    @Test
    fun rowsMirrorInARightToLeftInterface() {
        content(rtl = true) { ValueRow("Version", "1.0.0 (1)") }
        assertNear(row().left + px(16), bounds("1.0.0 (1)").left, "value end (left in RTL)")
        assertNear(row().right - px(16), bounds("Version").right, "label start (right in RTL)")
    }

    /** A long value wraps in the room the label leaves; it never squeezes a short label. */
    @Test
    fun aLongValueWrapsBesideAShortLabel() {
        val long = "Ethereum (Mainnet), Arbitrum One, Base, BNB Smart Chain and every other EVM network"
        content { ValueRow("Network", long) }
        val label = compose.onNodeWithText("Network", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(1, layout(label).lineCount, "the label stays on one line")
        assertTrue(layout(compose.onNodeWithText(long, useUnmergedTree = true).fetchSemanticsNode()).lineCount > 1)
        assertNear(row().right - px(16), bounds(long).right, "value end")
    }

    /**
     * Untranslated values are laid out in their own direction right to left ("Ethereum (Mainnet)" keeps its bracket at
     * the end); translated ones keep the interface direction (Compose's default, `TextDirection.Unspecified`).
     */
    @Test
    fun verbatimValuesKeepTheirOwnDirection() {
        content(rtl = true) {
            Column {
                ValueRow("Network", "Ethereum (Mainnet)", verbatim = true)
                NavRow("Language", null, onClick = {}, value = "Português (Brasil)", verbatimValue = true)
                ValueRow("Risk", "High (unverified)")
                NavRow("Theme", null, onClick = {}, value = "System (Dark)")
            }
        }
        fun layoutOf(text: String) = layout(compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode())
        for (verbatim in listOf("Ethereum (Mainnet)", "Português (Brasil)")) {
            assertEquals(TextDirection.Content, layoutOf(verbatim).layoutInput.style.textDirection, verbatim)
            assertEquals(ResolvedTextDirection.Ltr, layoutOf(verbatim).getParagraphDirection(0), verbatim)
        }
        for (translated in listOf("High (unverified)", "System (Dark)")) {
            assertEquals(TextDirection.Unspecified, layoutOf(translated).layoutInput.style.textDirection, translated)
        }
    }

    @Test
    fun widthsAreSharedByNeed() {
        assertEquals(80 to 120, labelValueWidths(available = 300, label = 80, value = 120), "both fit: natural widths")
        assertEquals(80 to 220, labelValueWidths(available = 300, label = 80, value = 500), "a short label keeps its width")
        assertEquals(200 to 100, labelValueWidths(available = 300, label = 400, value = 100), "a short value keeps its width")
        assertEquals(150 to 150, labelValueWidths(available = 300, label = 400, value = 500), "two long texts share")
        assertEquals(0 to 0, labelValueWidths(available = 0, label = 10, value = 10))
    }

    private fun layout(node: androidx.compose.ui.semantics.SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }
}
