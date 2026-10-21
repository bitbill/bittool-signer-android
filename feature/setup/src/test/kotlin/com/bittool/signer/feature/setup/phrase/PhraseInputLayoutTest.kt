package com.bittool.signer.feature.setup.phrase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.Look
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.PhraseAnalysis
import com.bittool.signer.feature.setup.unclippedBounds
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The import editor, its status line and the suggestion row as drawn (QA setup-03, setup-05, setup-07, setup-10). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h800dp-xhdpi")
class PhraseInputLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()
    private val words = Bip39Candidates.Words(listOf("abandon", "ability", "able", "about", "above"))

    private fun suggestionBar(look: Look = Look.Light) {
        compose.setContent {
            SetupTestHost(factory, look) {
                // The bar sits on the page colour (BottomActionBar).
                Box(Modifier.fillMaxWidth().background(SignerTheme.colors.page).testTag("bar")) {
                    RecoverySuggestionBar(words, onInsert = {}, onDone = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun Color.distanceTo(other: Color): Float = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)

    /** QA setup-03: in light mode the chips were the bar's own colour (`raised` = `page` = #F2F2F7), no tap affordance. */
    @Test
    fun suggestionChipsStandOutFromTheBarInBothThemes() {
        for ((look, palette) in listOf(Look.Light to SignerColors.Light, Look.Dark to SignerColors.Dark)) {
            assertTrue(suggestionChipColor(palette).distanceTo(palette.page) > 0.05f, "$look: the chip colour is the bar's")
        }
        suggestionBar(Look.Light)
        val chip = compose.onNodeWithTag("suggestion.abandon").captureToImage().toPixelMap()
        // Inside the capsule, left of the word.
        val fill = chip[with(compose.density) { 4.dp.roundToPx() }, chip.height / 2]
        assertTrue(fill.distanceTo(SignerColors.Light.page) > 0.05f, "the chip is drawn in the bar's colour ($fill)")
    }

    /** QA setup-10: the last chip was cut mid-word right against "Done"; the row now ends 8 dp before it. */
    @Test
    fun theChipsEndBeforeDone() {
        suggestionBar()
        val row = compose.onNodeWithTag(SUGGESTIONS_TAG).unclippedBounds()
        val done = compose.onNodeWithTag("recovery.keyboardDone").unclippedBounds()
        assertTrue(done.left - row.right >= with(compose.density) { 8.dp.toPx() } - 0.5f, "the chips run into Done ($row, $done)")
    }

    /**
     * QA setup-05: at font scale 2.0 the status lost "language" without an ellipsis ("12–24 words · any BIP39"). Below
     * the label it now has the full width and two lines.
     */
    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun theStatusIsNeverCutSilentlyAtLargeText() {
        compose.setContent { SetupTestHost(factory, Look.FontScale2) { RecoveryPhraseStatus(PhraseAnalysis.Empty) } }
        compose.waitForIdle()
        val status = compose.onNodeWithTag(STATUS_TEXT_TAG, useUnmergedTree = true)
        val layout = status.textLayout()
        assertFalse(layout.hasVisualOverflow && !layout.isLineEllipsized(layout.lineCount - 1), "the status is cut without an ellipsis")
        assertFalse(layout.hasVisualOverflow, "the whole status fits on two lines below the label")
        val label = compose.onNodeWithText("Recovery phrase", useUnmergedTree = true).unclippedBounds()
        assertTrue(status.unclippedBounds().top >= label.bottom, "stacked below the label when it does not fit beside it")
    }

    @Test
    fun theStatusSitsBesideTheLabelWhenItFits() {
        compose.setContent { SetupTestHost(factory) { RecoveryPhraseStatus(PhraseAnalysis.Empty) } }
        compose.waitForIdle()
        val status = compose.onNodeWithTag(STATUS_TEXT_TAG, useUnmergedTree = true)
        val label = compose.onNodeWithText("Recovery phrase", useUnmergedTree = true).unclippedBounds()
        assertTrue(status.unclippedBounds().top < label.bottom, "side by side")
        assertFalse(status.textLayout().hasVisualOverflow)
    }

    /**
     * QA setup-07: right to left, the words sat flush right with a ragged left edge (the text box is as wide as the
     * words and was placed at the start, the right edge). The editor is left to right as a whole.
     */
    @Test
    fun theEditorKeepsItsWordsAtTheLeftEdgeRightToLeft() {
        val state = TextFieldState("abandon ability")
        compose.setContent { SetupTestHost(factory, Look.Rtl) { RecoveryPhraseEditor(state, onFocusChanged = {}) } }
        compose.waitForIdle()
        val image = compose.onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()
        // Dark look: the words are light on the dark card.
        fun inkIn(from: Int, to: Int): Boolean = (from until to).any { x -> (0 until image.height).any { y -> image[x, y].luminance() > 0.5f } }
        val quarter = image.width / 4
        assertTrue(inkIn(0, quarter), "no words at the left edge")
        assertFalse(inkIn(image.width - quarter, image.width), "words at the right edge")
    }

    /**
     * QA setup-07: the placeholder is a translated sentence, so unlike the words it keeps the interface direction: in
     * Arabic it starts at the right edge (iOS overlays it `.topLeading`), it does not follow the editor to the left.
     */
    @Test
    @Config(qualifiers = "ar-w393dp-h800dp-xhdpi")
    fun thePlaceholderStartsAtTheInterfacesStartRightToLeft() {
        compose.setContent { SetupTestHost(factory, Look.Rtl) { RecoveryPhraseEditor(TextFieldState(), onFocusChanged = {}) } }
        compose.waitForIdle()
        val image = compose.onNodeWithTag(EDITOR_TAG).captureToImage().toPixelMap()
        val card = SignerColors.Dark.card
        val inset = with(compose.density) { 14.dp.roundToPx() }
        // The first line of the placeholder: 16 dp padding, then the line.
        val rows = with(compose.density) { 18.dp.roundToPx() until 36.dp.roundToPx() }
        val ink = (0 until image.width).filter { x -> rows.any { y -> image[x, y].distanceTo(card) > 0.15f } }
        assertTrue(ink.isNotEmpty(), "no placeholder drawn")
        val margin = with(compose.density) { 6.dp.toPx() }
        assertTrue(ink.max() >= image.width - inset - margin, "the placeholder ends at ${ink.max()}, short of the right edge (${image.width - inset})")
        assertTrue(ink.min() > inset + margin, "the placeholder starts at the left edge (${ink.min()})")
    }
}
