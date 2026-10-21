package com.bittool.signer.feature.sign.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.sign.ReviewStates
import com.bittool.signer.feature.sign.common.SignTags
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit A10: the engine emits a Details row per Bitcoin input and output, so a batch payout carries thousands. The
 * Details card was one item of the review list: when it came into view, every row was composed and measured on the
 * main thread in one frame (seconds for a few thousand rows: "isn't responding"). Each row is now its own item, and
 * only the rows on screen exist. The QA phone (393 × 807 dp) less its tab bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h727dp-xxhdpi")
class LargeReviewTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = ReviewStates.batchPayout
    private val rows = state.review!!.fields.size

    private fun show() = compose.setContent {
        SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
            RequestReviewContent(state, onReject = {}, onToggleRisk = {}, onRetry = {}, onAuthorize = {})
        }
    }

    /** The Details rows that exist now (composed), found by their engine labels. */
    private fun composedRows(): Int =
        compose.onAllNodes(hasText("Output ", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun aBatchPayoutComposesOnlyTheDetailsRowsOnScreen() {
        assertTrue(rows > 4_000, "precondition: $rows rows")
        show()
        val list = compose.onNodeWithTag(SignTags.REVIEW_LIST)
        // The heading at the top of the page: the card fills the rest of it.
        list.performScrollToIndex(DETAILS_HEADING)
        compose.onNodeWithText("Details").assertIsDisplayed()
        val atTheHeading = composedRows()
        assertTrue(atTheHeading in 1..60, "$atTheHeading of $rows rows composed with Details in view")
        list.performScrollToIndex(FIRST_ROW + 80)
        val further = composedRows()
        assertTrue(further in 1..60, "$further of $rows rows composed after scrolling")
    }

    @Test
    fun theLastRowIsReachedAndTheFingerprintFollowsIt() {
        show()
        val list = compose.onNodeWithTag(SignTags.REVIEW_LIST)
        // The fingerprint is the item after the rows.
        list.performScrollToIndex(FIRST_ROW + rows)
        compose.onNodeWithText("Request fingerprint").assertIsDisplayed()
        compose.onNodeWithText("Network fee").assertIsDisplayed()
        assertTrue(composedRows() <= 60)
    }

    /** One card: each row's slice of it touches the next, with no gap between the items. */
    @Test
    fun theRowsOfTheCardTouch() {
        show()
        compose.onNodeWithTag(SignTags.REVIEW_LIST).performScrollToIndex(FIRST_ROW + 4)
        val slices = compose.onAllNodesWithTag(SignTags.REVIEW_FIELD).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .sortedBy { it.top }
        assertTrue(slices.size >= 4, "${slices.size} rows on screen")
        slices.zipWithNext().forEach { (upper, lower) ->
            assertTrue(abs(lower.top - upper.bottom) < 0.5f, "a ${lower.top - upper.bottom} px gap between two rows")
            assertEquals(upper.left, lower.left)
            assertEquals(upper.right, lower.right)
        }
    }

    /** "Show All" is kept by its row while it scrolls far away and back (it is saved with the row's item). */
    @Test
    fun anExpandedRowStaysExpandedAfterScrollingAwayAndBack() {
        show()
        val list = compose.onNodeWithTag(SignTags.REVIEW_LIST)
        // The witness script row (the second field) at the top of the page, clear of the pinned Sign bar.
        list.performScrollToIndex(FIRST_ROW + 1)
        compose.onNodeWithText("Show All").assertIsDisplayed().performClick()
        compose.onNodeWithText("Show Less").assertIsDisplayed()
        list.performScrollToIndex(FIRST_ROW + rows)
        compose.onNodeWithText("Show Less").assertDoesNotExist()
        list.performScrollToIndex(FIRST_ROW + 1)
        compose.onNodeWithText("Show Less").assertIsDisplayed()
        compose.onNodeWithText("Show All").assertDoesNotExist()
    }

    private companion object {
        /** The list's items: the summary, the signing wallets, the Details heading, then one per field. */
        const val DETAILS_HEADING = 2
        const val FIRST_ROW = 3
    }
}
