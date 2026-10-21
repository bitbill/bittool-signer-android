package com.bittool.signer.core.ui.review

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.ui.legal.LegalDocumentView
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.registry.ContractCategory
import com.bittool.signer.core.model.registry.KnownContractEntry
import com.bittool.signer.core.model.review.ReviewContract
import com.bittool.signer.core.model.review.ReviewField
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.ui.catalog.CatalogSamples
import com.bittool.signer.core.ui.text.rememberReviewText
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.l10n.R
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared review building blocks (scan-sign.md §11.3–§11.9) and KnownContractTests' glyph case. */
@RunWith(RobolectricTestRunner::class)
class ReviewComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) {
        compose.setContent { SignerTheme(darkTheme = false, applySystemBarAppearance = false) { Column { body() } } }
    }

    /** iOS KnownContractTests `everyCategoryHasItsOwnGlyph`. */
    @Test
    fun everyCategoryHasItsOwnGlyph() {
        val listed = ContractCategory.entries
        val glyphs = listed.associateWith(::contractCategoryIcon)
        assertEquals(listed.size, glyphs.values.toSet().size, "two categories share a glyph: $glyphs")
        // An import that adds a category still renders, with the neutral glyph.
        val later = KnownContractEntry("evm", "1", "0x" + "1".repeat(40), "Future", "Future", "restaking")
        assertSame(SignerIcons.GridView, contractCategoryIcon(later.categoryKind))
    }

    @Test
    fun aLibraryHitIsAmberAndNamedByRole() {
        val review = CatalogSamples.approvalReview
        content { ReviewSummaryCard(review, rememberReviewText()) }
        // The spender is the counterparty, so the notice sits in the summary's counterparty box (compact).
        assertTrue(ReviewPresentation.showsNoticeInSummary(review))
        compose.onNode(hasTestTag(CONTRACT_NOTICE_TAG) and hasText("Known public contract (spender)")).assertExists()
        compose.onNode(hasTestTag(CONTRACT_NOTICE_TAG) and hasText("Permit2 — Uniswap · infra")).assertExists()
        compose.onNodeWithText("Same address on every EVM network.").assertExists()
        // High risk and partial decoding are both on the card.
        compose.onNodeWithText("High risk").assertExists()
        compose.onNodeWithText("Partially decoded").assertExists()
        // The fee ceiling never rounds below what the request allows (ReviewText.shortDecimal).
        compose.onNodeWithText("Network fee up to 0.00021491 ETH").assertExists()
    }

    @Test
    fun unlistedAddressesSayWhatTheyAre() {
        content {
            ContractNotice(CatalogSamples.unknownSpender)
            ContractNotice(ReviewContract(CatalogSamples.SPENDER, ReviewContract.Role.Operator, known = false))
            ContractNotice(ReviewContract(CatalogSamples.SPENDER, ReviewContract.Role.Contract, known = false))
            ContractNotice(CatalogSamples.tokenContract)
        }
        compose.onNodeWithText("Spender is not a known contract", substring = true).assertExists()
        compose.onNodeWithText("Operator is not a known contract", substring = true).assertExists()
        compose.onNodeWithText("Contract is not in the known list", substring = true).assertExists()
        compose.onNode(hasText("Recognized token contract") and hasText("Tether USD (USDT)")).assertExists()
        assertEquals(4, compose.onAllNodesWithTag(CONTRACT_NOTICE_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun warningsAreVerbatimAndSevereOnesAreDanger() {
        val warnings = listOf("UNLIMITED approval for USDT.", "HIGH value transfer.", "Unusual gas limit.")
        assertEquals(listOf(true, false, false), warnings.map(ReviewPresentation::isSevereWarning))
        // Activity's stored rule treats any HIGH prefix as severe, and UNLIMITED as a warning.
        assertEquals(listOf(false, true, false), warnings.map(ReviewPresentation::isSevereStoredWarning))
        content { ReviewWarnings(warnings) }
        warnings.forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test
    fun longValuesCollapseBehindShowAll() {
        val long = ReviewField(0, "Data", "ab".repeat(400))
        assertEquals(ReviewPresentation.DetailRowStyle.Collapsible, ReviewPresentation.rowStyle(long))
        content { ReviewDetailsCard(listOf(long), rememberReviewText()) }
        compose.onNodeWithText(ReviewPresentation.collapsedValue(long.value)).assertExists()
        compose.onNodeWithText("Show All").performClick()
        compose.onNodeWithText(long.value).assertExists()
        compose.onNodeWithText("Show Less").performClick()
        compose.onNodeWithText(ReviewPresentation.collapsedValue(long.value)).assertExists()
    }

    /**
     * In a right-to-left interface, untranslated text (engine warnings, decoded values, the English legal documents)
     * keeps its own direction, so an English sentence's full stop stays at its end; translated text keeps the
     * interface direction.
     */
    @Test
    fun verbatimEnglishKeepsItsOwnDirectionInARightToLeftInterface() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        ReviewWarnings(listOf("UNLIMITED approval for USDT."))
                        ReviewDetailsCard(listOf(ReviewField(0, "Method", "transfer (ERC-20).")), rememberReviewText())
                        LegalDocumentView(listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, "You alone hold your keys.")), Modifier.height(80.dp))
                        Callout("تم إنشاء التوقيع.")
                    }
                }
            }
        }
        fun direction(text: String): ResolvedTextDirection {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            return layouts.single().getParagraphDirection(0)
        }
        assertEquals(ResolvedTextDirection.Ltr, direction("UNLIMITED approval for USDT."))
        assertEquals(ResolvedTextDirection.Ltr, direction("transfer (ERC-20)."))
        assertEquals(ResolvedTextDirection.Ltr, direction("You alone hold your keys."))
        assertEquals(ResolvedTextDirection.Rtl, direction("تم إنشاء التوقيع."))
    }

    /**
     * QA sign-02 (Review in Arabic): what is signed is engine text, and the summary forced it right to left, so the
     * signed message read ".public interoperability test" and the amount "BTC 0.0008". Untranslated engine text keeps
     * its own direction; a phrase the catalogue translates ("Sign Message") stays right to left.
     */
    @Test
    @Config(qualifiers = "ar")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theSummaryKeepsEngineTextInItsOwnDirectionRightToLeft() {
        val message = "BitTool Wallet + BitTool Signer: public interoperability test."
        val personal = CatalogSamples.review(display = """{"action": "Sign Message", "headline": "$message"}""")
        val transfer = CatalogSamples.review(
            display = """{"action": "Send Bitcoin", "amount": "0.0008", "symbol": "BTC", "headline": "To 2 addresses (change returns to you)."}""",
        )
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column {
                        ReviewSummaryCard(personal, rememberReviewText())
                        ReviewSummaryCard(transfer, rememberReviewText())
                    }
                }
            }
        }
        fun layout(text: String): TextLayoutResult {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            return layouts.single()
        }
        // The message and the amount are selectable: their reported layout is the drawn one.
        assertEquals(ResolvedTextDirection.Ltr, layout(message).getParagraphDirection(0), "the signed message")
        val amount = layout("0.0008 BTC")
        assertEquals(ResolvedTextDirection.Ltr, amount.getParagraphDirection(0), "the amount")
        // "0.0008" is drawn before "BTC", not after it.
        assertTrue(amount.getBoundingBox(0).left < amount.getBoundingBox(amount.layoutInput.text.length - 1).left)
        // The other lines are plain text, whose reported layout is rebuilt from the unresolved style: checked on the
        // direction they are given (an unspecified one is the interface's, right to left).
        assertEquals(TextDirection.Content, layout("To 2 addresses (change returns to you).").layoutInput.style.textDirection, "the secondary headline")
        assertEquals(TextDirection.Content, layout("Send Bitcoin").layoutInput.style.textDirection, "an untranslated action")
        val translated = ApplicationProvider.getApplicationContext<Context>().getString(R.string.sign_message)
        assertTrue(translated != "Sign Message", "the Arabic catalogue translates the action")
        assertEquals(TextDirection.Unspecified, layout(translated).layoutInput.style.textDirection, "a translated action")
    }

    @Test
    fun theDefaultTitleAndOverflowLinesAreTranslatable() {
        val review = CatalogSamples.review(
            display = """{"action": "Review request", "assetKind": "nftOrder", "assetContract": "0xBC4CA0EdA7647A8aB7C2061c2E118A18a936f13D", "give": [${(1..40).joinToString { "\"NFT #$it\"" }}]}""",
        )
        content { ReviewSummaryCard(review, rememberReviewText()) }
        compose.onNodeWithText("Review request").assertExists()
        compose.onNodeWithText("NFT contract (not verified)").assertExists()
        compose.onNodeWithText("+9 more (see Details)", substring = true).assertExists()
    }
}
