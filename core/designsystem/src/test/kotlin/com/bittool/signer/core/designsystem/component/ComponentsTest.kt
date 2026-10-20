package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Behaviour of the generic components (iOS `Design/DesignSystem.swift`). */
@RunWith(RobolectricTestRunner::class)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { SignerTheme(darkTheme = false, applySystemBarAppearance = false) { Box { body() } } }
    }

    @Test
    fun aBusyButtonKeepsItsLabelAndIgnoresTaps() {
        var taps = 0
        var busy by mutableStateOf(true)
        content { PrimaryButton("Sign with Main", onClick = { taps++ }, icon = SignerIcons.Signature, busy = busy) }
        // The label stays laid out (invisible) so the width is stable and TalkBack still reads it.
        compose.onNodeWithText("Sign with Main", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Sign with Main")).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        compose.onNode(hasText("Sign with Main")).performClick()
        assertEquals(0, taps)
        busy = false
        compose.onNode(hasText("Sign with Main")).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun aDisabledButtonCannotBeTapped() {
        var taps = 0
        content { SecondaryButton("Cannot Sign", onClick = { taps++ }, enabled = false) }
        compose.onNode(hasText("Cannot Sign")).assertIsNotEnabled()
        assertEquals(0, taps)
    }

    @Test
    fun theSegmentedControlIsAGroupOfTabs() {
        var selected by mutableIntStateOf(0)
        var calls = 0
        content {
            SegmentedControl(listOf("Wallet Level", "Coin Level"), selected, { selected = it; calls++ })
        }
        compose.onNodeWithText("Wallet Level").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithText("Coin Level").assertIsNotSelected().performClick()
        assertEquals(1, selected)
        compose.onNodeWithText("Coin Level").assertIsSelected().performClick()
        assertEquals(1, calls, "selecting the current segment again does nothing")
    }

    @Test
    fun aToastStaysForOnePointSixSecondsAndANewerOneReplacesIt() {
        val state = StatusToastState()
        content { StatusToastHost(state) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.show("Copied") }
        advance(500)
        compose.onNodeWithText("Copied").assertExists()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.runOnIdle { state.show("Password Changed") }
        advance(1_200)
        compose.onNodeWithText("Password Changed").assertExists()
        compose.onNodeWithText("Copied").assertDoesNotExist()
        advance(SignerMotion.TOAST_MILLIS)
        assertNull(state.current, "dismissed 1.6 s after it appeared")
        advance(1_000)
        compose.onNodeWithText("Password Changed").assertDoesNotExist()
    }

    /** Advances the paused clock in frame-sized steps, letting snapshot changes and effects run in between. */
    private fun advance(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 100L)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            left -= step
        }
    }

    @Test
    fun anOlderTimerNeverDismissesANewerToast() {
        val state = StatusToastState()
        state.show("Copied")
        val first = state.current!!
        state.show("Copied")
        state.dismiss(first)
        assertEquals("Copied", state.current?.message)
    }

    @Test
    fun switchRowsToggleAsOneControl() {
        var on by mutableStateOf(false)
        content { SwitchRow("App Lock", SignerIcons.ShieldLock, on, { on = it }) }
        compose.onNodeWithText("App Lock").assertIsOff().performClick()
        compose.onNodeWithText("App Lock").assertIsOn()
        assertEquals(true, on)
    }

    @Test
    fun navRowsAreButtonsAndCalloutsReadAsOneElement() {
        var opened = 0
        content {
            androidx.compose.foundation.layout.Column {
                NavRow("Language", SignerIcons.Language, onClick = { opened++ }, value = "English")
                Callout("Read every warning above before signing.", title = "Review", tone = Tone.Warning)
            }
        }
        compose.onNodeWithText("Language").performClick()
        assertEquals(1, opened)
        // Title and text merge into one node (iOS `.accessibilityElement(children: .combine)`).
        compose.onNode(hasText("Review") and hasText("Read every warning above before signing.")).assertExists()
    }

    /**
     * iOS tab roots use large titles: `.largeTitle` bold (34 / 41), not Material's 28 sp regular headline. The bar grows
     * with the font scale, so the title is laid out whole (Material clips a title taller than its fixed 88 dp row).
     */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun largeTitlesAreTheIosLargeTitleAndAreNeverClipped() {
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                    SignerTopAppBar(title = "Settings", navigation = NavIcon.None, large = true, modifier = Modifier.testTag("bar"))
                }
            }
        }
        for (scale in listOf(1f, 1.3f, 2f)) {
            fontScale = scale
            compose.waitForIdle()
            val title = compose.onNodeWithText("Settings").fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val style = layouts.single().layoutInput.style
            assertEquals(34.sp, style.fontSize, "large title size at $scale")
            assertEquals(FontWeight.Bold, style.fontWeight)
            val shown = title.boundsInRoot
            assertTrue(shown.height >= title.size.height - 1f, "title clipped at font scale $scale: $shown vs ${title.size}")
            val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
            assertTrue(shown.bottom <= bar.bottom + 1f, "title outside the bar at $scale")
        }
    }

    /** A long trailing value never crushes the row title (the title keeps its own share of the row). */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text metrics
    fun aLongRowValueLeavesTheTitleReadable() {
        content {
            Column(Modifier.width(360.dp)) {
                NavRow(
                    title = "Language",
                    icon = SignerIcons.Language,
                    onClick = {},
                    value = "Português (Brasil) — a very long value that would take the whole row",
                    modifier = Modifier.testTag("row"),
                )
            }
        }
        val title = compose.onNodeWithText("Language", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(title.size.width > 0, "the title has room")
        val layouts = mutableListOf<TextLayoutResult>()
        title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(1, layout.lineCount, "the title stays on one line")
        assertTrue(!layout.isLineEllipsized(0) && layout.getLineRight(0) <= title.size.width + 1f, "the title is not cut")
        // The value still shows, end-aligned in its share.
        compose.onNodeWithTag("row").assertExists()
    }

    /**
     * iOS `ActionLabel` at accessibility sizes: the label takes two lines and the icon stays beside it, the pair
     * centred in the capsule. A wrapped Compose paragraph spans the whole width, which used to strand the icon at the
     * far edge.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aWrappedButtonLabelKeepsItsIconBesideIt() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                    Box(Modifier.width(360.dp)) {
                        SecondaryButton("Import Recovery Phrase", onClick = {}, icon = SignerIcons.Key, modifier = Modifier.testTag("button"))
                    }
                }
            }
        }
        val label = compose.onNodeWithText("Import Recovery Phrase", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(2, layout.lineCount, "two lines at an accessibility size")
        // Where the lines really are, in the root (the text node itself may be laid out wider than they are).
        val origin = label.positionInRoot.x
        val textLeft = origin + (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
        val textRight = origin + (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        val button = compose.onNodeWithTag("button").fetchSemanticsNode().boundsInRoot
        // Icon (20 dp) + gap (8 dp) + the lines, centred in the capsule as one group.
        val px = compose.density.density
        val iconAndGap = 28f * px
        val expectedLeft = button.left + (button.width - (iconAndGap + textRight - textLeft)) / 2f + iconAndGap
        assertTrue(kotlin.math.abs(textLeft - expectedLeft) <= 2f, "text starts at $textLeft, expected $expectedLeft")
        // The icon is drawn just before the text: the first ink on the capsule's middle row is within the 20 dp icon
        // box that ends 8 dp before the lines (it used to sit 20 dp from the capsule's edge).
        val image = compose.onNodeWithTag("button").captureToImage().toPixelMap()
        val row = image.height / 2
        val fill = image[(12f * px).toInt(), row]
        val firstInk = ((12f * px).toInt() until image.width).first { x ->
            val c = image[x, row]
            kotlin.math.abs(c.red - fill.red) + kotlin.math.abs(c.green - fill.green) + kotlin.math.abs(c.blue - fill.blue) > 0.2f
        }
        val iconBoxLeft = textLeft - button.left - iconAndGap
        assertTrue(firstInk >= iconBoxLeft - 1f && firstInk <= iconBoxLeft + 20f * px, "icon ink at $firstInk, icon box from $iconBoxLeft")
    }

    /** iOS plays `.success` with every status toast; a warning toast (an overlay blocked a tap) must not. */
    @Test
    fun aToastCarriesTheHapticOfItsMeaning() {
        val state = StatusToastState()
        state.show("Copied")
        assertEquals(HapticKind.Success, state.current?.haptic)
        state.show("Another app is drawing over BitTool Signer.", SignerIcons.WarningFilled, HapticKind.Warning)
        assertEquals(HapticKind.Warning, state.current?.haptic)
        state.show("Shown quietly", haptic = null)
        assertNull(state.current?.haptic)
    }
}
