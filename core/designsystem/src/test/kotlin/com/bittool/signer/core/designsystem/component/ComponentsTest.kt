package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.SignerTypography
import com.bittool.signer.core.designsystem.theme.containsRightToLeftLetters
import com.bittool.signer.core.designsystem.theme.localizedDirection
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
        // Icon (20 dp beside the 17 sp label, scaled with the label's font) + gap (8 dp) + the lines, centred in the
        // capsule as one group.
        val px = compose.density.density
        val icon = Density(px, 2f).run { 17.sp.toDp().value } * 20f / 17f * px
        val iconAndGap = icon + 8f * px
        val expectedLeft = button.left + (button.width - (iconAndGap + textRight - textLeft)) / 2f + iconAndGap
        assertTrue(kotlin.math.abs(textLeft - expectedLeft) <= 2f, "text starts at $textLeft, expected $expectedLeft")
        // The icon is drawn just before the text: the first ink on the capsule's middle row is within the icon box that
        // ends 8 dp before the lines (it used to sit 20 dp from the capsule's edge).
        val image = compose.onNodeWithTag("button").captureToImage().toPixelMap()
        val row = image.height / 2
        val fill = image[(12f * px).toInt(), row]
        val firstInk = ((12f * px).toInt() until image.width).first { x ->
            val c = image[x, row]
            kotlin.math.abs(c.red - fill.red) + kotlin.math.abs(c.green - fill.green) + kotlin.math.abs(c.blue - fill.blue) > 0.2f
        }
        val iconBoxLeft = textLeft - button.left - iconAndGap
        assertTrue(firstInk >= iconBoxLeft - 1f && firstInk <= iconBoxLeft + icon, "icon ink at $firstInk, icon box from $iconBoxLeft")
    }

    /**
     * QA setup-12: a button's leading symbol follows the font size like the label (iOS symbols scale with Dynamic
     * Type): 20 dp beside the 17 sp label, and as much larger as the label at font scale 2, where it used to stay 20 dp
     * next to text of nearly twice the size. Measured as the content width left of the label: padding 20 + icon + 8.
     */
    @Test
    fun aButtonIconScalesWithTheFont() {
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                    Box {
                        PrimaryButton("Create", onClick = {}, icon = SignerIcons.Add, fillWidth = false, modifier = Modifier.testTag("button"))
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            fontScale = scale
            compose.waitForIdle()
            val labelDp = Density(compose.density.density, scale).run { 17.sp.toDp().value }
            val expected = maxOf(labelDp, 17f) * 20f / 17f
            val button = compose.onNodeWithTag("button").fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText("Create", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val icon = (label.left - button.left) / compose.density.density - 20f - 8f
            assertEquals(expected, icon, 1f, "icon size at font scale $scale")
        }
    }

    /**
     * QA setup-12, the other buttons with a glyph beside text: the tonal pill's symbol follows its subheadline label
     * (iOS `CompactActionLabelStyle` sets the symbol's font), 18 dp at the default size and larger with the font, also at
     * accessibility sizes, where the pill shows the symbol alone. Measured as the pill's width: 12 dp padding each side.
     */
    @Test
    fun aPillIconScalesWithTheFont() {
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                    Box { TonalPillButton("Pair", SignerIcons.QrCode, onClick = {}, modifier = Modifier.testTag("pill")) }
                }
            }
        }
        for (scale in listOf(1.6f, 2f)) {
            fontScale = scale
            compose.waitForIdle()
            val labelDp = Density(compose.density.density, scale).run { 15.sp.toDp().value }
            val expected = labelDp * 18f / 15f
            val pill = compose.onNodeWithTag("pill").fetchSemanticsNode().boundsInRoot
            val icon = pill.width / compose.density.density - 2 * 12f
            assertEquals(expected, icon, 1f, "icon size at font scale $scale")
        }
    }

    /**
     * QA setup-11: iOS draws `checkmark.seal.fill` at the font size `size × 0.62`, which covers 0.406 of the disc's
     * diameter on the Done step's screenshot, against 0.331 for the Material glyph in a box of that size. Counted here
     * on fully inked rows only (no anti-aliased edge), the old glyph covers 0.31 and the iOS-sized one 0.38.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theHeroGlyphCoversItsDiscLikeTheIosSymbol() {
        content { SymbolHero(SignerIcons.VerifiedFilled, color = SignerColorsForTest.success, size = 64.dp, modifier = Modifier.testTag("hero")) }
        val image = compose.onNodeWithTag("hero").captureToImage().toPixelMap()
        val tone = SignerColorsForTest.success
        fun isGlyph(x: Int, y: Int): Boolean {
            val c = image[x, y]
            return kotlin.math.abs(c.red - tone.red) + kotlin.math.abs(c.green - tone.green) + kotlin.math.abs(c.blue - tone.blue) < 0.12f
        }
        val rows = (0 until image.height).filter { y -> (0 until image.width).any { x -> isGlyph(x, y) } }
        val glyph = (rows.last() - rows.first() + 1).toFloat()
        val disc = 64f * 1.7f * compose.density.density
        val share = glyph / disc
        assertTrue(share in 0.36f..0.42f, "the glyph covers $share of the disc")
    }

    /** QA sign-11: a sheet holding a toast host keeps its content height; the toast still shows inside it. */
    @Test
    fun aToastHostDoesNotGrowTheBoxItIsIn() {
        val state = StatusToastState()
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                // The room a bottom sheet measures its content in.
                Box(Modifier.height(600.dp)) {
                    Box(Modifier.testTag("sheet")) {
                        Box(Modifier.fillMaxWidth().height(120.dp))
                        StatusToastHost(state)
                    }
                }
            }
        }
        compose.onNodeWithTag("sheet").assertHeightIsEqualTo(120.dp)
        compose.runOnIdle { state.show("Copied") }
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.onNodeWithTag("sheet").assertHeightIsEqualTo(120.dp)
        val toast = compose.onNodeWithText("Copied").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(toast.bottom <= sheet.bottom - 24f * compose.density.density + 1f, "the toast sits 24 dp above the bottom")
    }

    /**
     * QA sign-02 (Settings in Arabic): a section footer whose translation is still missing is English; laid out right to
     * left its full stop moved to the front. English keeps its own direction; an Arabic footer keeps the interface
     * direction, also when it starts with the Latin product name.
     *
     * Checked on the style each footer is laid out with: the layout that Compose hands to tests through semantics for a
     * plain `Text` is rebuilt from the unresolved style, so it never shows the interface direction an unspecified
     * direction resolves to when drawn.
     */
    @Test
    fun sectionFootersFollowTheirLanguageInARightToLeftInterface() {
        val english = "Every signature still needs the wallet's password."
        val arabic = "لغة الواجهة."
        val product = "BitTool Signer لا يتصل بالإنترنت."
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        GroupedSection(footer = english) { item { Box(Modifier.height(10.dp)) } }
                        GroupedSection(footer = arabic) { item { Box(Modifier.height(10.dp)) } }
                        GroupedSection(footer = product) { item { Box(Modifier.height(10.dp)) } }
                    }
                }
            }
        }
        fun direction(text: String): TextDirection {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            return layouts.single().layoutInput.style.textDirection
        }
        assertEquals(TextDirection.Content, direction(english), "English: its own direction")
        assertEquals(TextDirection.Unspecified, direction(arabic), "Arabic: the interface direction")
        assertEquals(TextDirection.Unspecified, direction(product), "Arabic starting in Latin: the interface direction")
    }

    /** The rule behind [localizedDirection]: only right-to-left text in a right-to-left interface keeps its direction. */
    @Test
    fun localizedDirectionLeavesOnlyRightToLeftTranslationsToTheInterface() {
        val style = SignerTheme_typographyForTest.footnote
        assertEquals(TextDirection.Content, style.localizedDirection("Reject.", LayoutDirection.Rtl).textDirection)
        assertEquals(TextDirection.Unspecified, style.localizedDirection("رفض.", LayoutDirection.Rtl).textDirection)
        assertEquals(TextDirection.Unspecified, style.localizedDirection("BitTool Signer رفض.", LayoutDirection.Rtl).textDirection)
        // Left to right, every text keeps its own direction (an Arabic wallet name in an English sentence stays inside it).
        assertEquals(TextDirection.Content, style.localizedDirection("رفض.", LayoutDirection.Ltr).textDirection)
        assertEquals(TextDirection.Content, style.localizedDirection("Delete “محفظتي”?", LayoutDirection.Ltr).textDirection)
        assertTrue(containsRightToLeftLetters("שלום"))
        assertTrue(!containsRightToLeftLetters("0.0008 BTC · 12 words"))
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

/** The light palette the tests draw with. */
private val SignerColorsForTest = SignerColors.Light

/** The type scale the tests draw with. */
private val SignerTheme_typographyForTest = SignerTypography.Default
