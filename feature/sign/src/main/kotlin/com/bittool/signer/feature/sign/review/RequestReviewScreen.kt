package com.bittool.signer.feature.sign.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.DecodeStatus
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.ui.address.HashText
import com.bittool.signer.core.ui.review.ContractNotice
import com.bittool.signer.core.ui.review.ReviewDetailsCard
import com.bittool.signer.core.ui.review.ReviewSummaryCard
import com.bittool.signer.core.ui.review.ReviewWarnings
import com.bittool.signer.core.ui.text.rememberReviewText
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.rememberLatest
import com.bittool.signer.feature.sign.common.rememberOverlayBlocked
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * The request review (inventory rows 51–53): [RequestReviewContent] plus its password sheet and the "Discard partial
 * signatures?" confirmation, all driven by [RequestReviewViewModel].
 */
@Composable
fun RequestReviewScreen(
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
    viewModel: RequestReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RequestReviewContent(
        state = state,
        onReject = viewModel::reject,
        onToggleRisk = viewModel::toggleRisk,
        onRetry = viewModel::retryMatching,
        onAuthorize = viewModel::openAuthorize,
        modifier = modifier,
        scrollToTop = scrollToTop,
    )
    state.authorize?.let { authorize ->
        AuthorizeSheet(
            state = authorize,
            onSign = viewModel::sign,
            onCancel = viewModel::cancelAuthorize,
            onDismissed = viewModel::onAuthorizeDismissed,
        )
    }
    if (state.confirmReject) {
        SignerAlertDialog(
            title = stringResource(R.string.discard_partial_signatures),
            text = stringResource(R.string.signatures_already_created_for_this_request_will_be),
            confirm = DialogAction(text = stringResource(R.string.reject_request), destructive = true, onClick = viewModel::confirmReject),
            dismiss = DialogAction(text = stringResource(R.string.cancel), onClick = viewModel::dismissReject),
            onDismissRequest = viewModel::dismissReject,
        )
    }
}

/**
 * The review page (iOS `RequestReviewView`, `Features/Sign/RequestReviewView.swift:5-217`; scan-sign.md §11): "Review
 * Request" with "Reject"; the summary, the known-contract card when the summary does not carry it, the engine's
 * warnings (verbatim), the signing wallets, every decoded field and the request fingerprint; the acknowledgement and
 * Sign pinned at the bottom. While the page leaves (Reject, the last signature) it keeps showing the last request
 * instead of going blank.
 *
 * The bar exists only when it has something to show (nothing while the wallet is being matched), and it slides in
 * when matching ends. At large text sizes the acknowledgement would take most of a phone's height above Sign
 * ([acknowledgementPinned]): it then scrolls as the last item of the page, directly above the bar, so the request
 * itself stays readable; Sign stays pinned and disabled until it is ticked, and until then the bar says where the box
 * is (a tap scrolls there). Where the acknowledgement goes is decided in the page's own measure pass, before anything
 * is drawn, so it never shows up in the bar first and then moves (backlog B32).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestReviewContent(
    state: RequestReviewUiState,
    onReject: () -> Unit,
    onToggleRisk: () -> Unit,
    onRetry: () -> Unit,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
    listState: LazyListState = rememberLazyListState(),
) {
    val shown = rememberLatest(state) { it.review != null }
    val review = shown.review ?: return
    val reviewText = rememberReviewText()
    val toast = rememberStatusToastState()
    val blocked = rememberOverlayBlocked(toast)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(scrollToTop, listState) { scrollToTop.collect { listState.animateScrollToItem(0) } }
    val fullyDecoded = review.decodeStatus == DecodeStatus.FullyDecoded
    // The bar's horizontal frame (`BottomActionBar`: side navigation bars, page gutters, readable width) for the copies
    // of its parts that are only measured. They are never placed, so never drawn, and they carry no semantics.
    val probeFrame = Modifier
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
        .padding(horizontal = SignerMetrics.pagePadding)
        .readableWidth()
        .clearAndSetSemantics {}
    SubcomposeLayout(modifier.testTag(SignTags.REVIEW)) { constraints ->
        val pinned = !shown.showsAcknowledgement || acknowledgementPinned(
            acknowledgementHeight = measureProbe(ReviewSlot.AcknowledgementProbe, constraints) {
                AcknowledgementLayout(shown.riskAccepted, fullyDecoded, probeFrame, animated = false)
            },
            actionsHeight = measureProbe(ReviewSlot.ActionsProbe, constraints) {
                SignActions(shown, onAuthorize = {}, onObscuredTouch = {}, modifier = probeFrame, interactive = false)
            },
            pageHeight = constraints.maxHeight,
        )
        val page = subcompose(ReviewSlot.Page) {
            ReviewPage(shown, review, reviewText, pinned, listState, scrollBehavior, toast, blocked, onReject, onToggleRisk, onRetry, onAuthorize)
        }.map { it.measure(constraints) }
        val width = page.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = page.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) { page.forEach { it.place(0, 0) } }
    }
}

/** The page's slots: the review itself and the two measurement-only copies of the bar's parts. */
private enum class ReviewSlot { Page, AcknowledgementProbe, ActionsProbe }

/** The height of [content] laid out at the page's width: measured, never placed. */
private fun SubcomposeMeasureScope.measureProbe(slot: ReviewSlot, constraints: Constraints, content: @Composable () -> Unit): Int =
    subcompose(slot, content).maxOfOrNull { it.measure(Constraints(maxWidth = constraints.maxWidth)).height } ?: 0

/**
 * Where the risk acknowledgement goes (QA sign-04; design-system "use stacking at large font scales rather than
 * truncating"): pinned above Sign while the bar it makes stays within [MAX_PINNED_BAR_FRACTION] of the page, else in the
 * scrolling content. Decided from heights that do not depend on the decision (the acknowledgement and the actions
 * under it as the bar lays them out, measured on their own and never busy), so it never flips back and forth. The
 * bar's own chrome (8 dp above and below) and the gap between the two parts are left out: they are the same in both
 * placements and small next to the threshold. A page of unbounded height keeps it pinned.
 */
private fun acknowledgementPinned(acknowledgementHeight: Int, actionsHeight: Int, pageHeight: Int): Boolean =
    pageHeight == Constraints.Infinity || acknowledgementHeight + actionsHeight <= MAX_PINNED_BAR_FRACTION * pageHeight

/** The largest share of the review page the pinned acknowledgement and Sign may take together. */
private const val MAX_PINNED_BAR_FRACTION = 0.35f

/** The page with the acknowledgement where [RequestReviewContent] put it ([pinned]: in the bar, else the last item). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewPage(
    shown: RequestReviewUiState,
    review: TransactionReview,
    reviewText: ReviewText,
    pinned: Boolean,
    listState: LazyListState,
    scrollBehavior: TopAppBarScrollBehavior,
    toast: StatusToastState,
    blocked: () -> Unit,
    onReject: () -> Unit,
    onToggleRisk: () -> Unit,
    onRetry: () -> Unit,
    onAuthorize: () -> Unit,
) {
    val reduceMotion = SignerTheme.reduceMotion
    val scope = rememberCoroutineScope()
    Box {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = SignerTheme.colors.page,
            topBar = {
                SignerTopAppBar(
                    title = stringResource(R.string.review_request),
                    navigation = NavIcon.None,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TextAction(
                            text = stringResource(R.string.reject),
                            onClick = onReject,
                            destructive = true,
                            modifier = Modifier.testTag(SignTags.REJECT),
                        )
                    },
                )
            },
            bottomBar = {
                // iOS draws an empty bar while matching; here no strip and hairline wait above the tab bar.
                AnimatedVisibility(
                    visible = shown.hasSignBar,
                    enter = if (reduceMotion) EnterTransition.None else barEnter,
                    exit = if (reduceMotion) ExitTransition.None else barExit,
                ) {
                    BottomActionBar(canScrollUnder = listState.canScrollForward, modifier = Modifier.testTag(SignTags.SIGN_BAR)) {
                        SignBar(
                            shown,
                            onToggleRisk = onToggleRisk,
                            onAuthorize = onAuthorize,
                            onObscuredTouch = blocked,
                            acknowledgementInBar = pinned,
                            onShowAcknowledgement = {
                                // The acknowledgement is the last item: scrolling to it ends the list, right above the bar.
                                scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(SignTags.REVIEW_LIST),
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                reviewSections(shown, review, reviewText, onRetry)
                if (!pinned && shown.showsAcknowledgement) {
                    item(key = "acknowledgement", contentType = "acknowledgement") {
                        RiskAcknowledgement(
                            checked = shown.riskAccepted,
                            fullyDecoded = review.decodeStatus == DecodeStatus.FullyDecoded,
                            onToggle = onToggleRisk,
                            onObscuredTouch = blocked,
                            modifier = Modifier.readableWidth().padding(horizontal = SignerMetrics.pagePadding),
                        )
                    }
                }
            }
        }
        StatusToastHost(toast)
    }
}

/** The bar rises from the bottom edge while the list makes room for it (as the tab bar does). */
private val barEnter = slideInVertically(SignerMotion.reveal()) { it } +
    expandVertically(SignerMotion.reveal(), expandFrom = Alignment.Top) + fadeIn(SignerMotion.reveal())
private val barExit = slideOutVertically(SignerMotion.reveal()) { it } +
    shrinkVertically(SignerMotion.reveal(), shrinkTowards = Alignment.Top) + fadeOut(SignerMotion.reveal())

private fun LazyListScope.reviewSections(
    state: RequestReviewUiState,
    review: TransactionReview,
    reviewText: ReviewText,
    onRetry: () -> Unit,
) {
    val page = Modifier.readableWidth().padding(horizontal = SignerMetrics.pagePadding)
    item(key = "summary", contentType = "summary") { ReviewSummaryCard(review, reviewText, page) }
    // The summary already carries the notice when the authorized address is its counterparty; a token transfer
    // authorizes the token contract instead, a different address with its own card.
    val contract = review.contract
    if (contract != null && ReviewPresentation.showsNoticeCard(review)) {
        item(key = "contract", contentType = "contract") { ContractNotice(contract, page) }
    }
    if (review.warnings.isNotEmpty()) {
        item(key = "warnings", contentType = "warnings") { ReviewWarnings(review.warnings, page) }
    }
    item(key = "wallets", contentType = "wallets") { SigningWalletsSection(state, review, onRetry, page) }
    item(key = "details", contentType = "details") { ReviewDetailsCard(review.fields, reviewText, page) }
    item(key = "fingerprint", contentType = "fingerprint") { RequestFingerprint(review.requestHash, page) }
}

/** "Request fingerprint" and the hash in groups of 8 (`RequestReviewView.swift:28-33`). */
@Composable
private fun RequestFingerprint(hash: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.request_fingerprint),
            style = SignerTheme.typography.caption.semibold(),
            color = SignerTheme.colors.secondaryLabel,
        )
        HashText(hash)
    }
}
