package com.bittool.signer.feature.sign.tab

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.feature.sign.home.ScanHomeScreen
import com.bittool.signer.feature.sign.home.ScanHomeViewModel
import com.bittool.signer.feature.sign.result.SignatureResultScreen
import com.bittool.signer.feature.sign.result.SignatureResultViewModel
import com.bittool.signer.feature.sign.result.rememberSignatureExport
import com.bittool.signer.feature.sign.review.RequestReviewScreen
import com.bittool.signer.feature.sign.review.RequestReviewViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Scan tab root (`ScanRoute`, inventory row 45; iOS `ScanTab`): Home, the request review or the signature result,
 * chosen from `SignerState` and cross-faded with `.smooth(0.3)` (ARCHITECTURE §4.6); the review stays up while "Match
 * Again" re-reviews the request ([ScanStage.shown]). A camera request made elsewhere
 * is reconciled whenever this page appears and whenever the request token changes; [onOpenScanner] presents the
 * camera. System Back is left to the tab glue (Wallets), so it never discards a reviewed request or a signature.
 *
 * The signature's "Export as File" picker is registered here, not on the result page: its answer can arrive in a new
 * process that shows Home ([rememberSignatureExport]).
 */
@Composable
fun ScanTabScreen(
    onOpenScanner: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanTabViewModel = hiltViewModel(),
    homeViewModel: ScanHomeViewModel = hiltViewModel(),
    reviewViewModel: RequestReviewViewModel = hiltViewModel(),
    resultViewModel: SignatureResultViewModel = hiltViewModel(),
) {
    val current by viewModel.stage.collectAsStateWithLifecycle()
    val retrying by reviewViewModel.retrying.collectAsStateWithLifecycle()
    val stage = current.shown(retrying)
    val scanRequest by viewModel.scanRequest.collectAsStateWithLifecycle()
    val latestOpen by rememberUpdatedState(onOpenScanner)
    val openScanner: () -> Unit = remember(homeViewModel) {
        {
            homeViewModel.onOpenScanner()
            latestOpen()
        }
    }
    LaunchedEffect(scanRequest) {
        if (viewModel.consumeScanRequest()) openScanner()
    }
    val exportSignature = rememberSignatureExport(resultViewModel)
    val scrollToTop: Flow<Unit> = remember(viewModel) { viewModel.reselections.map { } }
    AnimatedContent(
        targetState = stage,
        modifier = modifier,
        transitionSpec = { fadeIn(SignerMotion.contentSwap()) togetherWith fadeOut(SignerMotion.contentSwap()) },
        label = "scanStage",
    ) { shown ->
        when (shown) {
            ScanStage.Home -> ScanHomeScreen(viewModel = homeViewModel, onOpenScanner = openScanner, scrollToTop = scrollToTop)
            ScanStage.Review -> RequestReviewScreen(scrollToTop = scrollToTop, viewModel = reviewViewModel)
            ScanStage.Signature -> SignatureResultScreen(scrollToTop = scrollToTop, viewModel = resultViewModel, onExport = exportSignature)
        }
    }
}
