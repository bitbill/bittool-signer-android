package com.bittool.signer.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import java.time.LocalDate
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Activity layouts at font scale 2.0 (ARCHITECTURE §4.7: no clipped or overlapping text at the largest sizes). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ActivityLargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(fontScale: Float = 2f, content: @Composable () -> Unit) = compose.setContent {
        val density = LocalDensity.current
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    @Test
    fun atLargeTextARowTitleIsNotCutShortByTheTime() {
        // At accessibility sizes the time moves under the subtitle so a two-line title keeps the row's full width.
        val entry = ActivityEntry(
            id = "00000000-0000-4000-8000-0000000000aa",
            title = "Approve token spending",
            summary = "Unlimited USDT to Permit2",
            wallet = WalletLabel.Named("Public fixture 1"),
            dateMillis = ActivityTestData.utc(20, 17, 42),
            chain = Chain.Evm,
            chainId = "1",
        )
        show {
            ActivityContent(
                state = ActivityUiState(loading = false, days = listOf(ActivityDay(LocalDate.of(2026, 10, 20), listOf(entry)))),
                onOpenReceipt = {},
                onClear = {},
                onConfirmClear = {},
                onDismissClear = {},
            )
        }
        val title = layoutOf("Approve token spending")
        assertFalse((0 until title.lineCount).any { title.isLineEllipsized(it) }, "the title is shown in full")
    }

    @Test
    @Config(qualifiers = "in-w411dp-h891dp-xxhdpi")
    fun theSignatureSheetTitleAndDoneNeverOverlap() {
        // Indonesian "Tanda tangan" / "Selesai": a long title beside a long action, at font scale 2.0.
        val titleText = RuntimeEnvironment.getApplication().getString(R.string.signature)
        // The sheet is its own window: it takes the system font scale, not the composition's density.
        RuntimeEnvironment.setFontScale(2f)
        show(fontScale = 1f) { SignatureQrSheet(frames = listOf("UR:ETH-SIGNATURE/OEADTPDAGDNDCAWMGTFRKIGRPMNDUTDNBTKGFSSBJNAOHDCX"), onDismiss = {}) }
        compose.mainClock.advanceTimeBy(2_000)
        val titleNode = compose.onNodeWithText(titleText, useUnmergedTree = true)
        val title = titleNode.fetchSemanticsNode().boundsInRoot
        val done = compose.onNodeWithTag(ActivityDetailTags.QR_DONE).fetchSemanticsNode().boundsInRoot
        assertFalse(title.overlaps(done), "title $title overlaps Done $done")
        val layout = layoutOf(titleText)
        assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) }, "the title is shown in full")
    }
}
