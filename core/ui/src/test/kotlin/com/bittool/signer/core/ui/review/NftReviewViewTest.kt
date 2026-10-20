package com.bittool.signer.core.ui.review

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.review.ReviewAssetKind
import com.bittool.signer.core.model.review.ReviewDisplay
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.ui.address.COPY_BUTTON_TAG
import com.bittool.signer.core.ui.catalog.CatalogSamples
import com.bittool.signer.core.ui.text.rememberReviewText
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The four view cases of iOS `NFTReviewDisplayTests` (`BitToolSignerTests/NFTReviewDisplayTests.swift:302-419`):
 * glyph labels, every engine display rendering in the summary card, raw-unit prices wrapping instead of truncating,
 * and token IDs in Details rendered as plain text. Cards are laid out 353 dp wide with 20 dp padding, as iOS renders them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text metrics: wrapping is what these cases measure
class NftReviewViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Composable
    private fun Frame(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        SignerTheme(darkTheme = dark, applySystemBarAppearance = false) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                // Unbounded height: the test window is shorter than a large-text card, which must not be clipped to it.
                Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) {
                    Box(Modifier.background(SignerTheme.colors.page).padding(20.dp).width(353.dp).testTag("frame")) { content() }
                }
            }
        }
    }

    /** iOS `glyphAndSpokenLabels` (glyph half; the spoken-ID half lives in `:core:model`). */
    @Test
    fun glyphAndSpokenLabels() {
        assertSame(SignerIcons.PhotoFrame, ReviewAssetKind.Nft.glyph)
        assertSame(SignerIcons.PhotoFrame, ReviewAssetKind.NftOrToken.glyph)
        // ERC-1155 IDs may be fungible: a neutral stack, never called an NFT.
        assertSame(SignerIcons.Stack, ReviewAssetKind.MultiToken.glyph)
        assertSame(SignerIcons.Tag, ReviewAssetKind.NftOrder.glyph)
        assertEquals("NFT", context.getString(ReviewAssetKind.Nft.accessibilityLabel))
        assertEquals("ERC-1155 token", context.getString(ReviewAssetKind.MultiToken.accessibilityLabel))
        assertEquals("NFT or token", context.getString(ReviewAssetKind.NftOrToken.accessibilityLabel))
        assertEquals("NFT order", context.getString(ReviewAssetKind.NftOrder.accessibilityLabel))
        compose.setContent {
            Frame { androidx.compose.foundation.layout.Row { ReviewAssetKind.entries.forEach { NftGlyph(it, Modifier.testTag(it.wire)) } } }
        }
        for (kind in ReviewAssetKind.entries) {
            compose.onNodeWithTag(kind.wire).assertContentDescriptionEquals(context.getString(kind.accessibilityLabel))
        }
        assertEquals("1234", ReviewDisplay.spokenTokenId("1234"))
        assertEquals("1234, 5678, 9012, 34", ReviewDisplay.spokenTokenId("12345678901234"))
    }

    /** iOS `summaryCardRendersEngineDisplays`: every fixture, light at the default size and dark at a very large size. */
    @Test
    fun summaryCardRendersEngineDisplays() {
        var shown by mutableStateOf(Triple(CatalogSamples.review(CatalogSamples.nftDisplays.values.first()), false, 1f))
        compose.setContent {
            val (review, dark, scale) = shown
            Frame(dark, scale) { ReviewSummaryCard(review, rememberReviewText()) }
        }
        for ((name, display) in CatalogSamples.nftDisplays.toSortedMap()) {
            val review = CatalogSamples.review(display)
            for ((dark, scale) in listOf(false to 1f, true to 3.1f)) {
                shown = Triple(review, dark, scale)
                compose.waitForIdle()
                val size = compose.onNodeWithTag("frame").fetchSemanticsNode().size
                assertTrue(size.width > 0 && size.height > 0, "$name renders")
                assertTrue(compose.onAllNodesWithTag(HEADLINE_TAG).fetchSemanticsNodes().isNotEmpty(), "$name has a headline")
                val parsed = review.display!!
                if (parsed.summaryTokenId != null) compose.onNodeWithTag("review.tokenId").assertExists()
                if (parsed.assetContract != null) compose.onNodeWithTag("review.assetContract").assertExists()
                for (section in parsed.flowSections) compose.onNodeWithTag("review.flow.${section.id}").assertExists()
            }
        }
    }

    /** iOS `rawUnitOrderPriceWrapsInsteadOfTruncating`: a 78-digit raw price takes several more large-title lines. */
    @Test
    fun rawUnitOrderPriceWrapsInsteadOfTruncating() {
        val raw = CatalogSamples.nftDisplays.getValue("seaportRawPrice")
        val short = raw.replace(
            "\"amount\": \"100000000000000000000000000000000000000000000000000000000000000000000000000000\", \"symbol\": \"raw units\"",
            "\"amount\": \"0.95\", \"symbol\": \"ETH\"",
        )
        assertTrue(short != raw)
        var review by mutableStateOf(CatalogSamples.review(raw))
        compose.setContent { Frame { ReviewSummaryCard(review, rememberReviewText()) } }
        // The whole number is on screen, not an ellipsis.
        compose.onNode(hasTestTag(HEADLINE_TAG) and hasText("100000000000000000000000000000000000000000000000000000000000000000000000000000 raw units")).assertExists()
        val rawHeight = compose.onNodeWithTag("frame").fetchSemanticsNode().size.height
        review = CatalogSamples.review(short)
        compose.waitForIdle()
        val shortHeight = compose.onNodeWithTag("frame").fetchSemanticsNode().size.height
        val line = 41f * context.resources.displayMetrics.density // the large title's leading at the default size
        assertTrue(rawHeight - shortHeight > 3 * line, "raw $rawHeight vs short $shortHeight, line $line")
    }

    /** iOS `detailsCardRendersTokenIDsAsPlainText`. */
    @Test
    fun detailsCardRendersTokenIDsAsPlainText() {
        val fields = CatalogSamples.detailFields.take(3)
        assertEquals(
            listOf(ReviewPresentation.DetailRowStyle.Identifier, ReviewPresentation.DetailRowStyle.Identifier, ReviewPresentation.DetailRowStyle.Address),
            fields.map(ReviewPresentation::rowStyle),
        )
        compose.setContent { Frame { ReviewDetailsCard(fields, rememberReviewText()) } }
        // Token IDs and item lists are shown whole, as plain text: no grouping, no copy button, no "Show All".
        compose.onNodeWithText(fields[0].value).assertExists()
        compose.onNodeWithText(fields[1].value).assertExists()
        compose.onNodeWithText("Show All").assertDoesNotExist()
        // Only the program address is an address row with its copy button.
        assertEquals(1, compose.onAllNodesWithTag(COPY_BUTTON_TAG).fetchSemanticsNodes().size)
        val size = compose.onNodeWithTag("frame").fetchSemanticsNode().size
        assertTrue(size.height > 0)
    }
}
