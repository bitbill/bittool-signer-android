package com.bittool.signer.feature.activity

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.feature.activity.ActivityTestData.receipt
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Activity pages rendered with Robolectric (English, light). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp-xhdpi")
class ActivityScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val id = "00000000-0000-4000-8000-0000000000aa"

    private fun detail(state: ActivityDetailUiState, onOpenDecoded: () -> Unit = {}, onShowQr: () -> Unit = {}) {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityDetailContent(
                    state = state,
                    onBack = {},
                    onOpenDecodedFields = onOpenDecoded,
                    onShowQr = onShowQr,
                    onDismissQr = {},
                    onExport = {},
                )
            }
        }
    }

    @Test
    fun theDecodedEssentialsNameTheActionAmountAndCounterparty() {
        // ActivityRecordTests.theDecodedEssentialsNameTheActionAmountAndCounterparty, at screen level: the Input
        // section names the action, the amount with its symbol, the engine's own counterparty caption with the short
        // address, and the fee — then offers every field.
        var opened = 0
        detail(
            ActivityDetailUiState(receipt = receipt(id), decode = DecodeState.Decoded(ActivityTestData.approvalReview)),
            onOpenDecoded = { opened++ },
        )
        val list = compose.onNodeWithTag(ActivityDetailTags.LIST)
        list.performScrollToNode(hasTestTag(ActivityDetailTags.DECODED))
        for (text in listOf("Action", "Approve Spending", "Amount", "Unlimited USDT", "Spender", "Network fee", "0.0012 ETH")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText(AddressFormatter.short(ActivityTestData.SPENDER, 6, 6), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ActivityDetailTags.DECODED).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aRequestWithoutDisplayStillNamesWhatItWas() {
        detail(ActivityDetailUiState(receipt = receipt(id), decode = DecodeState.Decoded(ActivityTestData.plainReview)))
        compose.onNodeWithTag(ActivityDetailTags.LIST).performScrollToNode(hasTestTag(ActivityDetailTags.DECODED))
        compose.onNodeWithText("Send bitcoin", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Amount", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun whileDecodingThePageSaysSoAndTheFingerprintIsShown() {
        detail(ActivityDetailUiState(receipt = receipt(id), decode = DecodeState.Decoding))
        compose.onNodeWithText("Request fingerprint", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(ActivityDetailTags.LIST).performScrollToNode(hasText("Decoding again…"))
        compose.onNodeWithTag(ActivityDetailTags.DECODED).assertDoesNotExist()
    }

    @Test
    fun outputOffersTheQrAgainTheExportAndThePayload() {
        var shown = 0
        detail(ActivityDetailUiState(receipt = receipt(id), decode = DecodeState.Decoding), onShowQr = { shown++ })
        val list = compose.onNodeWithTag(ActivityDetailTags.LIST)
        list.performScrollToNode(hasTestTag(ActivityDetailTags.SIGNATURE + "Toggle"))
        compose.onNodeWithTag(ActivityDetailTags.EXPORT).assertExists()
        compose.onNodeWithTag(ActivityDetailTags.SIGNATURE + "Toggle").performClick()
        compose.onNodeWithTag(ActivityDetailTags.SIGNATURE).assertExists()
        list.performScrollToNode(hasTestTag(ActivityDetailTags.SHOW_QR))
        compose.onNodeWithTag(ActivityDetailTags.SHOW_QR).performClick()
        assertEquals(1, shown)
    }

    /**
     * QA round 3 activity-keyboard-04: "Show QR Code" and "Export as File" are list buttons in the accent tint
     * (`ActivityTab.swift:246-256`, `build/ios-reference/light-31b-activity-input-output.png`), like the wallet page's
     * (regression-14), not label-coloured rows that read as navigation.
     */
    @Test
    fun theOutputActionsAreAccentButtonsLikeIos() {
        var accent = Color.Unspecified
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                accent = SignerTheme.colors.accent
                ActivityDetailContent(
                    state = ActivityDetailUiState(receipt = receipt(id), decode = DecodeState.Decoding),
                    onBack = {},
                    onOpenDecodedFields = {},
                    onShowQr = {},
                    onDismissQr = {},
                    onExport = {},
                )
            }
        }
        val list = compose.onNodeWithTag(ActivityDetailTags.LIST)
        mapOf(ActivityDetailTags.SHOW_QR to "Show QR Code", ActivityDetailTags.EXPORT to "Export as File").forEach { (tag, title) ->
            list.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals(accent, layouts.single().layoutInput.style.color, "“$title” is drawn in the accent")
        }
    }

    @Test
    fun anOlderRecordExplainsWhyOnlyItsSummaryRemains() {
        detail(ActivityDetailUiState(receipt = receipt(id, requestJson = null, frames = null, text = null), decode = DecodeState.NotStored))
        compose.onNodeWithTag(ActivityDetailTags.LIST)
            .performScrollToNode(hasText("The request and signature are kept for the 25 most recent signatures. This one is older, so only its summary remains."))
        compose.onNodeWithTag(ActivityDetailTags.SHOW_QR).assertDoesNotExist()
        compose.onNodeWithText("Input", useUnmergedTree = true).assertDoesNotExist()
    }

    /** "Signed Request" for a record that kept no request says why there is nothing to show; it never spins forever. */
    @Test
    fun aSignedRequestWithoutAStoredRequestExplainsInsteadOfWaiting() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                SignedRequestContent(decode = DecodeState.NotStored, onBack = {})
            }
        }
        compose.onNodeWithText(
            "The request and signature are kept for the 25 most recent signatures. This one is older, so only its summary remains.",
        ).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theListShowsRowsAndClear() {
        var opened: String? = null
        var cleared = 0
        val entry = ActivityEntry(id, "Approve token spending", "Unlimited USDT", WalletLabel.Named("Public fixture 1"), ActivityTestData.utc(20, 9), com.bittool.signer.core.model.Chain.Evm, "1")
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityContent(
                    state = ActivityUiState(loading = false, days = listOf(ActivityDay(LocalDate.of(2026, 10, 20), listOf(entry)))),
                    onOpenReceipt = { opened = it },
                    onClear = { cleared++ },
                    onConfirmClear = {},
                    onDismissClear = {},
                )
            }
        }
        // The row reads as one element that names the wallet (gaps.md G1.8 step 7).
        compose.onNodeWithTag(ActivityTags.ROW).assert(hasText("Unlimited USDT · Public fixture 1"))
        compose.onNodeWithTag(ActivityTags.ROW).performClick()
        assertEquals(id, opened)
        compose.onNodeWithTag(ActivityTags.CLEAR).performClick()
        assertEquals(1, cleared)
        compose.onNodeWithText("A local record of signatures created on this device. Check broadcast and confirmation in your other wallet.").assertExists()
    }

    /**
     * QA round 2 (activity pre-check): the day header is grey like the iOS section header (`light-30-activity.png`),
     * not accent-coloured, and stays a heading.
     */
    @Test
    fun theDayHeaderIsASecondaryLabelHeadingLikeIos() {
        val entry = ActivityEntry(id, "Send bitcoin", "0.015 BTC", WalletLabel.Named("Public fixture 1"), ActivityTestData.utc(20, 9), com.bittool.signer.core.model.Chain.Bitcoin, null)
        var secondaryLabel = Color.Unspecified
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                secondaryLabel = SignerTheme.colors.secondaryLabel
                ActivityContent(
                    state = ActivityUiState(loading = false, days = listOf(ActivityDay(LocalDate.of(2026, 10, 20), listOf(entry)))),
                    onOpenReceipt = {},
                    onClear = {},
                    onConfirmClear = {},
                    onDismissClear = {},
                )
            }
        }
        val header = compose.onNodeWithText("Tuesday, Oct 20, 2026", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val layouts = mutableListOf<TextLayoutResult>()
        header.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(secondaryLabel, layouts.single().layoutInput.style.color)
    }

    @Test
    fun theEmptyListSaysNoSignaturesYetWithoutClear() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityContent(ActivityUiState(loading = false), onOpenReceipt = {}, onClear = {}, onConfirmClear = {}, onDismissClear = {})
            }
        }
        compose.onNodeWithText("No Signatures Yet").assertIsDisplayed()
        compose.onNodeWithTag(ActivityTags.CLEAR).assertDoesNotExist()
    }

    @Test
    fun theClearConfirmationNamesWhatItRemoves() {
        var confirmed = 0
        val entry = ActivityEntry(id, "Send bitcoin", "", null, ActivityTestData.utc(20, 9), com.bittool.signer.core.model.Chain.Bitcoin, null)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityContent(
                    state = ActivityUiState(
                        loading = false,
                        days = listOf(ActivityDay(LocalDate.of(2026, 10, 20), listOf(entry))),
                        showsClearConfirmation = true,
                    ),
                    onOpenReceipt = {},
                    onClear = {},
                    onConfirmClear = { confirmed++ },
                    onDismissClear = {},
                )
            }
        }
        compose.onNodeWithText("Clear all activity?").assertIsDisplayed()
        compose.onNodeWithText("This only removes the local records. It does not affect any transaction.").assertIsDisplayed()
        compose.onNodeWithTag(ActivityTags.CLEAR_CONFIRM).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun reselectingTheTabScrollsToTheTopAndExpandsTheTitle() {
        // iOS: tapping the selected tab scrolls the list up and the large title comes back. A programmatic scroll does
        // not pass through the bar's nested scrolling, so the bar has to be expanded as well.
        val reselections = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val days = (18..20).map { day ->
            ActivityDay(
                LocalDate.of(2026, 10, day),
                (0 until 5).map { n ->
                    ActivityEntry("$day-$n", "Send bitcoin", "0.015 BTC", WalletLabel.Named("Public fixture 1"), ActivityTestData.utc(day, 9 + n), com.bittool.signer.core.model.Chain.Bitcoin, null)
                },
            )
        }
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityContent(
                    state = ActivityUiState(loading = false, days = days),
                    onOpenReceipt = {},
                    onClear = {},
                    onConfirmClear = {},
                    onDismissClear = {},
                    scrollToTop = reselections,
                )
            }
        }
        val firstRowTop = { compose.onAllNodesWithTag(ActivityTags.ROW)[0].fetchSemanticsNode().boundsInRoot.top }
        val expandedTop = firstRowTop()
        compose.onNodeWithTag(ActivityTags.LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()
        reselections.tryEmit(Unit)
        compose.waitForIdle()
        assertEquals(expandedTop, firstRowTop(), "the list is back at the top under the expanded large title")
    }

    @Test
    fun exportWithoutADocumentPickerShowsTheFailureInsteadOfCrashing() {
        // No app handles ACTION_CREATE_DOCUMENT (some work profiles): the launcher throws ActivityNotFoundException.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val store = FakeSignerStore(
            SignerState(isLoading = false, walletLoadSucceeded = true, wallets = listOf(ActivityTestData.wallet), receipts = listOf(receipt(id))),
        )
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val viewModel = ActivityDetailViewModel(SavedStateHandle(mapOf(ActivityDetailViewModel.RECEIPT_ID to id)), store) { _, _ -> }
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ActivityDetailScreen(onBack = {}, onOpenDecodedFields = {}, viewModel = viewModel)
            }
        }
        val list = compose.onNodeWithTag(ActivityDetailTags.LIST)
        list.performScrollToNode(hasTestTag(ActivityDetailTags.EXPORT))
        compose.onNodeWithTag(ActivityDetailTags.EXPORT).performClick()
        list.performScrollToNode(hasText("The file could not be saved."))
    }
}
