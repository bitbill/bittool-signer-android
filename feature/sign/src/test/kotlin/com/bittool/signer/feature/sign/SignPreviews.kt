package com.bittool.signer.feature.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.ElevatedSurfaceLevel
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.model.signing.SigningPlan
import com.bittool.signer.feature.sign.SignFixtures.walletA
import com.bittool.signer.feature.sign.SignFixtures.walletB
import com.bittool.signer.feature.sign.common.SheetHeader
import com.bittool.signer.feature.sign.home.PasteRequestBody
import com.bittool.signer.feature.sign.home.PasteUiState
import com.bittool.signer.feature.sign.home.ScanHomeContent
import com.bittool.signer.feature.sign.home.ScanHomeUiState
import com.bittool.signer.feature.sign.result.SignatureResultContent
import com.bittool.signer.feature.sign.result.SignatureResultUiState
import com.bittool.signer.feature.sign.review.AuthorizeBody
import com.bittool.signer.feature.sign.review.AuthorizeUiState
import com.bittool.signer.feature.sign.review.RequestReviewContent
import com.bittool.signer.feature.sign.review.RequestReviewUiState
import com.bittool.signer.feature.sign.review.SigningWallet
import com.bittool.signer.feature.sign.review.TaskItem

/** The looks every page is recorded in (design-system.md §9.14). */
enum class Look(val dark: Boolean, val fontScale: Float, val rtl: Boolean) {
    Light(false, 1f, false),
    Dark(true, 1f, false),
    FontScale2(false, 2f, false),
    Rtl(true, 1f, true),
}

@Composable
fun InLook(look: Look, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    SignerTheme(darkTheme = look.dark, applySystemBarAppearance = false) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, look.fontScale),
            LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Box(Modifier.fillMaxSize().background(SignerTheme.colors.page)) { content() }
        }
    }
}

/** Review page states built from the fixtures, as `RequestReviewViewModel` would publish them. */
object ReviewStates {
    private val wallets = SignFixtures.walletsState()

    private fun state(
        review: TransactionReview,
        plan: SigningPlan? = null,
        completed: Set<String> = emptySet(),
        issue: MatchingIssue? = null,
        riskAccepted: Boolean = false,
        busy: Boolean = false,
    ): RequestReviewUiState {
        val s: SignerState = wallets.copy(review = review, plan = plan, completedTaskIds = completed, matchingIssue = issue, busy = busy)
        val tasks = plan?.tasks?.map { TaskItem(it, SigningWallet.of(s, it.walletId), it.id in completed) }
        return RequestReviewUiState(
            review = review,
            matchingIssue = issue,
            tasks = tasks,
            pending = s.pendingTasks.firstOrNull()?.let { first -> tasks?.firstOrNull { it.task.id == first.id } },
            completedCount = completed.size,
            busy = busy,
            hasSignedResponse = false,
            riskAccepted = riskAccepted,
        )
    }

    val matching = state(SignFixtures.bitcoinSend)
    val bitcoinReady = state(SignFixtures.bitcoinSend, SignFixtures.plan("task-1" to walletA))
    val approval = state(SignFixtures.knownSpenderApproval, SignFixtures.plan("task-1" to walletA))
    val approvalAcknowledged = state(SignFixtures.knownSpenderApproval, SignFixtures.plan("task-1" to walletA), riskAccepted = true)
    val partial = state(SignFixtures.partiallyDecoded, SignFixtures.plan("task-1" to walletA))
    val multiWallet = state(
        SignFixtures.bitcoinSend,
        SignFixtures.plan("task-a" to walletA, "task-b" to walletB),
        completed = setOf("task-a"),
    )
    val noMatch = state(SignFixtures.bitcoinSend, issue = MatchingIssue.Engine("No matching wallet"))
    val interrupted = state(SignFixtures.knownSpenderApproval, issue = MatchingIssue.Interrupted)
    val signing = state(SignFixtures.bitcoinSend, SignFixtures.plan("task-1" to walletA), busy = true)

    val authorize = AuthorizeUiState("task-1", SigningWallet.of(wallets, walletA), riskAcknowledged = true)
    val authorizeFailed = authorize.copy(error = UiText.Ios("The password is incorrect."), attempt = 1)
}

/** Every recorded page, by file name. */
val SignPages: Map<String, @Composable () -> Unit> = linkedMapOf(
    "01-scan-home" to { ScanHomeContent(ScanHomeUiState(), onScan = {}, onPaste = {}, onImportFile = {}) },
    "02-scan-home-error" to {
        ScanHomeContent(ScanHomeUiState(busy = false, error = UiText.Ios("This file is larger than 512 KB.")), onScan = {}, onPaste = {}, onImportFile = {})
    },
    "03-paste-request" to { SheetPreview { PasteSheetPreview(PasteUiState(), "") } },
    "04-paste-request-error" to {
        SheetPreview { PasteSheetPreview(PasteUiState(error = UiText.Verbatim("Unsupported signing QR format")), "unsupported-qr-test-fixture") }
    },
    "10-review-matching" to { ReviewPreview(ReviewStates.matching) },
    "11-review-bitcoin" to { ReviewPreview(ReviewStates.bitcoinReady) },
    "12-review-known-spender" to { ReviewPreview(ReviewStates.approval) },
    "13-review-acknowledged" to { ReviewPreview(ReviewStates.approvalAcknowledged) },
    "14-review-partially-decoded" to { ReviewPreview(ReviewStates.partial) },
    "15-review-multi-wallet" to { ReviewPreview(ReviewStates.multiWallet) },
    "16-review-no-match" to { ReviewPreview(ReviewStates.noMatch) },
    "17-review-interrupted" to { ReviewPreview(ReviewStates.interrupted) },
    "18-review-signing" to { ReviewPreview(ReviewStates.signing) },
    "20-authorize" to { SheetPreview { AuthorizePreview(ReviewStates.authorize) } },
    "21-authorize-error" to { SheetPreview { AuthorizePreview(ReviewStates.authorizeFailed) } },
    "30-signature" to {
        SignatureResultContent(
            SignatureResultUiState(
                frames = SignFixtures.responseFrames,
                text = SignFixtures.responseFrames.joinToString("\n"),
                payload = SignFixtures.psbtPayload,
                review = SignFixtures.bitcoinSend,
            ),
            onExport = {},
            onDone = {},
        )
    },
    "31-signature-export-error" to {
        SignatureResultContent(
            SignatureResultUiState(
                frames = listOf("UR:ETH-SIGNATURE/OSADTPDAGDNDCAWMGTFRKIGRPMNDUTDNBTKGFSSBJNAOHDGRYKFWBKCNBSFTHEOEOEOEOE"),
                text = "UR:ETH-SIGNATURE/OSADTPDAGDNDCAWMGTFRKIGRPMNDUTDNBTKGFSSBJNAOHDGRYKFWBKCNBSFTHEOEOEOEOE",
                review = SignFixtures.knownSpenderApproval,
                exportError = UiText.Verbatim("No space left on device"),
            ),
            onExport = {},
            onDone = {},
        )
    },
)

@Composable
private fun ReviewPreview(state: RequestReviewUiState) {
    RequestReviewContent(state, onReject = {}, onToggleRisk = {}, onRetry = {}, onAuthorize = {})
}

/** A sheet's content on the elevated sheet colour, as `SignerBottomSheet` draws it (sheets are separate windows). */
@Composable
private fun SheetPreview(content: @Composable () -> Unit) {
    ElevatedSurfaceLevel {
        Box(Modifier.fillMaxSize().background(SignerTheme.colors.page)) { content() }
    }
}

@Composable
private fun PasteSheetPreview(state: PasteUiState, text: String) {
    val field = remember { TextFieldState(text) }
    Column {
        SheetHeader(title = stringResource(R.string.paste_request), onCancel = {})
        PasteRequestBody(state, field, onPasteFromClipboard = {})
        BottomActionBar { PrimaryButton(text = stringResource(R.string.review_request), onClick = {}, enabled = text.isNotBlank()) }
    }
}

@Composable
private fun AuthorizePreview(state: AuthorizeUiState) {
    val field = remember { TextFieldState() }
    Column {
        SheetHeader(title = stringResource(R.string.authorize_signature), onCancel = {})
        AuthorizeBody(state, field, onSubmit = {}, autoFocus = false)
        BottomActionBar { PrimaryButton(text = stringResource(R.string.sign), onClick = {}, icon = SignerIcons.Signature, enabled = false) }
    }
}
