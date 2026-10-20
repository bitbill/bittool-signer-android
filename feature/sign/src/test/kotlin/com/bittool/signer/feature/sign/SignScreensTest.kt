package com.bittool.signer.feature.sign

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.home.ScanHomeContent
import com.bittool.signer.feature.sign.home.ScanHomeUiState
import com.bittool.signer.feature.sign.result.SignatureResultContent
import com.bittool.signer.feature.sign.result.SignatureResultUiState
import com.bittool.signer.feature.sign.review.AuthorizeSheet
import com.bittool.signer.feature.sign.review.RequestReviewContent
import com.bittool.signer.feature.sign.review.RequestReviewUiState
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Scan tab's semantics and controls as the e2e suite and TalkBack meet them (scan-sign.md §20; gaps.md G1.8–G1.12):
 * test tags, labels, enabled states and what each control calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1800dp-xhdpi")
class SignScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun review(state: RequestReviewUiState, onToggle: () -> Unit = {}, onAuthorize: () -> Unit = {}, onRetry: () -> Unit = {}) {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                RequestReviewContent(state, onReject = {}, onToggleRisk = onToggle, onRetry = onRetry, onAuthorize = onAuthorize)
            }
        }
    }

    @Test
    fun theWholeScanCardIsOneButtonReadAsScanSigningRequest() {
        var scans = 0
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ScanHomeContent(ScanHomeUiState(), onScan = { scans++ }, onPaste = {}, onImportFile = {})
            }
        }
        compose.onNodeWithTag(SignTags.SCAN)
            .assertContentDescriptionEquals("Scan signing request")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, scans)
        // Read by its label only: the card's texts are not separate stops and not appended to the label.
        compose.onNodeWithTag(SignTags.SCAN).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        compose.onNodeWithText("Open Camera").assertDoesNotExist()
        compose.onNodeWithTag(SignTags.PASTE).assertIsEnabled()
        compose.onNodeWithTag(SignTags.FILE).assertIsEnabled()
        compose.onNodeWithText("How signing works").assertExists()
    }

    @Test
    fun theOptionTilesAreDisabledWhileAFileIsImported() {
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                ScanHomeContent(ScanHomeUiState(busy = true), onScan = {}, onPaste = {}, onImportFile = {})
            }
        }
        compose.onNodeWithTag(SignTags.PASTE).assertIsNotEnabled()
        compose.onNodeWithTag(SignTags.FILE).assertIsNotEnabled()
        compose.onNodeWithTag(SignTags.SCAN).assertIsEnabled()
    }

    @Test
    fun signStaysDisabledUntilTheRisksAreAcknowledged() {
        var toggles = 0
        review(ReviewStates.approval, onToggle = { toggles++ })
        compose.onNodeWithTag(SignTags.RISK)
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not checked"))
            .performClick()
        assertEquals(1, toggles)
        compose.onNodeWithTag(SignTags.AUTHORIZE).assertIsNotEnabled()
        compose.onNodeWithText("Sign with Public fixture 1").assertExists()
    }

    @Test
    fun anAcknowledgedRequestCanBeSigned() {
        var opened = 0
        review(ReviewStates.approvalAcknowledged, onAuthorize = { opened++ })
        compose.onNodeWithTag(SignTags.RISK)
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Checked"))
        compose.onNodeWithTag(SignTags.AUTHORIZE).assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theKnownSpenderNoticeIsOneElementNamingTheContract() {
        // G1.10: the merged notice names Permit2, Uniswap and "Known public contract".
        review(ReviewStates.approval)
        compose.onNodeWithTag("review.contractNotice")
            .assert(hasText("Known public contract", substring = true))
            .assert(hasText("Permit2", substring = true))
            .assert(hasText("Uniswap", substring = true))
        compose.onNodeWithText("Spender (known contract)").assertExists()
    }

    @Test
    fun anUnmatchedRequestExplainsWhyAndCannotBeSigned() {
        review(ReviewStates.noMatch)
        compose.onNodeWithTag(SignTags.MATCH_ERROR).assert(hasText("No matching wallet", substring = true))
        compose.onNodeWithText("This request does not belong to any wallet on this device. Check that you scanned the request for this wallet.").assertExists()
        compose.onNodeWithTag(SignTags.RETRY_MATCH).assertDoesNotExist()
        compose.onNodeWithTag(SignTags.AUTHORIZE).assertIsNotEnabled().assert(hasText("Cannot Sign"))
    }

    @Test
    fun anInterruptedMatchOffersMatchAgain() {
        var retries = 0
        review(ReviewStates.interrupted, onRetry = { retries++ })
        compose.onNodeWithTag(SignTags.MATCH_ERROR).assert(hasText("Matching interrupted. Try again.", substring = true))
        compose.onNodeWithTag(SignTags.RETRY_MATCH).performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag(SignTags.RISK).assertDoesNotExist()
    }

    @Test
    fun aMultiWalletRequestCountsTheSignatures() {
        review(ReviewStates.multiWallet)
        compose.onNodeWithText("Signature 2 of 2").assertExists()
        compose.onNodeWithText("Signing Wallets").assertExists()
        compose.onNodeWithText("Signed").assertExists()
        compose.onNodeWithText("Sign with Travel Signer").assertExists()
    }

    @Test
    fun whileMatchingTheWalletSectionWaitsAndNothingCanBeSigned() {
        review(ReviewStates.matching)
        compose.onNodeWithText("Finding the matching wallet…").assertExists()
        compose.onNodeWithTag(SignTags.AUTHORIZE).assertDoesNotExist()
    }

    @Test
    fun signMovesThePasswordIntoASecretAndClearsTheField() {
        var signed: String? = null
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                AuthorizeSheet(
                    state = ReviewStates.authorize,
                    onSign = { secret: SecretText -> signed = String(secret.chars()); secret.wipe() },
                    onCancel = {},
                    onDismissed = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SignTags.AUTHORIZE_SIGN).assertIsNotEnabled()
        compose.onNodeWithTag("Wallet password").performTextInput("Public-Test-Password1!")
        compose.onNodeWithTag(SignTags.AUTHORIZE_SIGN).assertIsEnabled().performClick()
        assertEquals("Public-Test-Password1!", signed)
        compose.onNodeWithTag(SignTags.AUTHORIZE_SIGN).assertIsNotEnabled()
        compose.onNodeWithText("The password decrypts this wallet for this signature only.").assertExists()
    }

    @Test
    fun theResultHasOneExportActionAndDone() {
        var exports = 0
        var done = 0
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                SignatureResultContent(
                    SignatureResultUiState(
                        frames = listOf("UR:ETH-SIGNATURE/OK"),
                        text = "UR:ETH-SIGNATURE/OK",
                        payload = SignFixtures.psbtPayload,
                        review = SignFixtures.bitcoinSend,
                    ),
                    onExport = { exports++ },
                    onDone = { done++ },
                )
            }
        }
        compose.onNodeWithTag(SignTags.SIGNATURE_TITLE).assert(hasText("Signature Created"))
        assertEquals(1, compose.onAllNodesWithText("Export as File").fetchSemanticsNodes().size)
        compose.onNodeWithTag(SignTags.SIGNATURE_PAYLOAD + "Toggle").assertExists()
        compose.onNodeWithTag(SignTags.SIGNATURE_EXPORT).performClick()
        compose.onNodeWithTag(SignTags.SIGNATURE_DONE).performClick()
        assertEquals(1, exports)
        assertEquals(1, done)
        compose.onNodeWithText("Sign Bitcoin transaction").assertExists()
    }
}
