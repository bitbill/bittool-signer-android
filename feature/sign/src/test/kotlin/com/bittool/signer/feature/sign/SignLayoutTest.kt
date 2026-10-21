package com.bittool.signer.feature.sign

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.UiText
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.home.PasteRequestBody
import com.bittool.signer.feature.sign.home.PasteUiState
import com.bittool.signer.feature.sign.home.ScanHomeContent
import com.bittool.signer.feature.sign.home.ScanHomeUiState
import com.bittool.signer.feature.sign.review.RequestReviewContent
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Scan tab layouts measured with real text (QA round 1, `docs/qa/round1-sign.md`): the QA phone (393 × 807 dp) less its
 * ~80 dp tab bar, so a page gets the height it has on the device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h727dp-xxhdpi")
class SignLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) = compose.setContent {
        val density = LocalDensity.current
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
    }

    private fun review(fontScale: Float, onToggle: () -> Unit = {}) = show(fontScale) {
        RequestReviewContent(ReviewStates.partial, onReject = {}, onToggleRisk = onToggle, onRetry = {}, onAuthorize = {})
    }

    /** The whole node lies inside every clipping parent (a scroll viewport, the window). */
    private fun SemanticsNodeInteraction.assertFullyVisible(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val shown = fetchSemanticsNode().boundsInRoot
        val whole = with(compose.density) { getUnclippedBoundsInRoot().let { it.height.toPx() } }
        assertTrue(abs(shown.height - whole) < 1f, "only ${shown.height} of $whole px are visible")
        return this
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    /**
     * QA sign-04: at font scale 2.0 the pinned acknowledgement and Sign took ~40 % of the phone, leaving a slot for the
     * request. The acknowledgement then scrolls as the last item above the bar; Sign stays pinned, disabled until ticked.
     */
    @Test
    fun atLargeTextTheAcknowledgementScrollsWithTheRequestInsteadOfFillingTheBar() {
        var toggles = 0
        review(fontScale = 2f, onToggle = { toggles++ })
        val page = compose.onNodeWithTag(SignTags.REVIEW).getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(SignTags.SIGN_BAR).getUnclippedBoundsInRoot()
        assertTrue(bar.height <= page.height * 0.35f, "the pinned bar takes ${bar.height} of the page's ${page.height}")
        compose.onNodeWithTag(SignTags.AUTHORIZE).assertIsDisplayed().assertIsNotEnabled()
        // Scrolled to the end, it is the last thing above the bar.
        val list = compose.onNodeWithTag(SignTags.REVIEW_LIST).performScrollToNode(hasTestTag(SignTags.RISK))
        repeat(3) { list.performTouchInput { swipeUp() } }
        val risk = compose.onNodeWithTag(SignTags.RISK).assertFullyVisible()
        assertTrue(risk.getUnclippedBoundsInRoot().bottom <= compose.onNodeWithTag(SignTags.SIGN_BAR).getUnclippedBoundsInRoot().top)
        risk.performClick()
        assertEquals(1, toggles)
    }

    /** At the default text size the acknowledgement stays directly above Sign, in the pinned bar (iOS). */
    @Test
    fun atTheDefaultTextSizeTheAcknowledgementIsPinnedAboveSign() {
        review(fontScale = 1f)
        compose.onNode(hasTestTag(SignTags.RISK) and hasAnyAncestor(hasTestTag(SignTags.SIGN_BAR))).assertFullyVisible()
        compose.onNodeWithTag(RISK_HINT).assertDoesNotExist()
    }

    private val riskInBar = hasTestTag(SignTags.RISK) and hasAnyAncestor(hasTestTag(SignTags.SIGN_BAR))

    /**
     * Backlog B32: where the acknowledgement goes is known before the first frame. It used to be measured in the bar
     * first, so the first frame showed it there, and the next one moved it into the list.
     */
    @Test
    fun atLargeTextTheFirstFrameAlreadyHasTheAcknowledgementInTheList() {
        compose.mainClock.autoAdvance = false
        review(fontScale = 2f)
        compose.onNode(riskInBar).assertDoesNotExist()
        compose.onNodeWithTag(SignTags.AUTHORIZE).assertExists()
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.onNode(riskInBar).assertDoesNotExist()
        }
    }

    /** Backlog B32, when matching ends: the bar slides in with Sign only; the acknowledgement never rides in with it. */
    @Test
    fun atLargeTextTheBarArrivingAfterMatchingNeverCarriesTheAcknowledgement() {
        var state by mutableStateOf(ReviewStates.matching)
        show(fontScale = 2f) { RequestReviewContent(state, onReject = {}, onToggleRisk = {}, onRetry = {}, onAuthorize = {}) }
        compose.onNodeWithTag(SignTags.SIGN_BAR).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        state = ReviewStates.partial
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            compose.onNode(riskInBar).assertDoesNotExist()
        }
        compose.onNodeWithTag(SignTags.SIGN_BAR).assertExists()
    }

    /**
     * Backlog B32: with the acknowledgement in the list the disabled Sign said nothing. The bar now says where the box
     * is, a tap there scrolls to it, and the line goes once the box is ticked.
     */
    @Test
    fun atLargeTextTheBarPointsToTheAcknowledgementUntilItIsTicked() {
        var state by mutableStateOf(ReviewStates.partial)
        show(fontScale = 2f) {
            RequestReviewContent(state, onReject = {}, onToggleRisk = { state = state.copy(riskAccepted = !state.riskAccepted) }, onRetry = {}, onAuthorize = {})
        }
        val hint = compose.onNode(hasTestTag(RISK_HINT) and hasAnyAncestor(hasTestTag(SignTags.SIGN_BAR)))
        hint.assertIsDisplayed()
            .assert(hasText("Check the box at the end of the review to sign."))
            .assertHasClickAction()
        val belowTheFold = compose.onAllNodesWithTag(SignTags.RISK).fetchSemanticsNodes().isEmpty() ||
            runCatching { compose.onNodeWithTag(SignTags.RISK).assertIsNotDisplayed() }.isSuccess
        assertTrue(belowTheFold, "precondition: the box is below the fold")
        hint.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SignTags.RISK).assertFullyVisible().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RISK_HINT).assertDoesNotExist()
        compose.onNodeWithTag(SignTags.AUTHORIZE).assertIsEnabled()
    }

    /** QA sign-06: the import error sits under the editor, which the keyboard covers; it is scrolled into view. */
    @Test
    fun aPasteErrorIsScrolledIntoViewWhenItAppears() {
        var error by mutableStateOf<UiText?>(null)
        show {
            // The part of the sheet above the keyboard and the pinned "Review Request".
            Column(Modifier.height(360.dp)) {
                PasteRequestBody(PasteUiState(error = error), remember { TextFieldState("garbage") }, onPasteFromClipboard = {}, Modifier.weight(1f, fill = false))
            }
        }
        error = UiText.Verbatim("Unsupported signing QR format")
        compose.onNodeWithText("Unsupported signing QR format", useUnmergedTree = true).assertFullyVisible()
    }

    /**
     * QA regression-05: above the keyboard the sheet shows little more than the editor, so bringing the error into view
     * scrolled the typed text and the caret under the sheet's header. The keyboard goes down first (here it gives the
     * sheet the height it takes), then the error comes into view with the whole editor still above it.
     */
    @Test
    fun aPasteErrorPutsTheKeyboardAwaySoTheTypedRequestStaysInView() {
        var error by mutableStateOf<UiText?>(null)
        var height by mutableStateOf(300.dp)
        var hidden = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit

            override fun hide() {
                hidden++
                height = 640.dp
            }
        }
        show {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                Column(Modifier.height(height).testTag(VIEWPORT)) {
                    PasteRequestBody(PasteUiState(error = error), remember { TextFieldState("garbage-request") }, onPasteFromClipboard = {}, Modifier.weight(1f, fill = false))
                }
            }
        }
        error = UiText.Verbatim("Unsupported signing QR format")
        compose.waitForIdle()
        assertEquals(1, hidden, "the keyboard is put away once")
        compose.onNodeWithText("Unsupported signing QR format", useUnmergedTree = true).assertFullyVisible()
        val viewport = compose.onNodeWithTag(VIEWPORT).getUnclippedBoundsInRoot()
        val editor = compose.onNodeWithTag(SignTags.PAYLOAD).getUnclippedBoundsInRoot()
        assertTrue(editor.top >= viewport.top, "the editor starts at ${editor.top}, above the sheet's top ${viewport.top}")
    }

    /** QA sign-07: an Import File error is laid out under the tiles, below the fold; it is scrolled into view. */
    @Test
    fun anImportErrorIsScrolledIntoViewWhenItAppears() {
        var state by mutableStateOf(ScanHomeUiState(busy = true))
        show {
            Box(Modifier.height(520.dp)) { ScanHomeContent(state, onScan = {}, onPaste = {}, onImportFile = {}) }
        }
        state = ScanHomeUiState(error = UiText.Ios("This file is larger than 512 KB."))
        compose.onNodeWithText("This file is larger than 512 KB.", useUnmergedTree = true).assertFullyVisible()
    }

    /**
     * QA sign-13: when only one tile label wraps (font scale 2.0 here, longer translations elsewhere) both symbols and
     * both labels still start on the same line, and labels break by phrase with balanced lines (no orphan glyph).
     */
    @Test
    fun theOptionTilesKeepTheirSymbolsInLineWhenOneLabelWraps() {
        show(fontScale = 2f) { ScanHomeContent(ScanHomeUiState(), onScan = {}, onPaste = {}, onImportFile = {}) }
        val paste = layoutOf("Paste Request")
        val file = layoutOf("Import File")
        assertNotEquals(paste.lineCount, file.lineCount, "precondition: exactly one label wraps")
        val pasteTop = compose.onNodeWithText("Paste Request", useUnmergedTree = true).getUnclippedBoundsInRoot().top
        val fileTop = compose.onNodeWithText("Import File", useUnmergedTree = true).getUnclippedBoundsInRoot().top
        assertEquals(pasteTop, fileTop, "both labels (and the symbols above them) start at the same height")
        assertEquals(LineBreak.Heading, paste.layoutInput.style.lineBreak)
        assertEquals(LineBreak.Heading, file.layoutInput.style.lineBreak)
    }

    private companion object {
        /** `SignTags.RISK_HINT`, spelled out. */
        const val RISK_HINT = "signing.riskHint"
        const val VIEWPORT = "test.viewport"
    }
}
