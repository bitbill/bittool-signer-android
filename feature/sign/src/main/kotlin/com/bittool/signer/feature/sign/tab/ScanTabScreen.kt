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
import com.bittool.signer.feature.sign.review.RequestReviewScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Scan tab root (`ScanRoute`, inventory row 45; iOS `ScanTab`): Home, the request review or the signature result,
 * chosen from `SignerState` and cross-faded with `.smooth(0.3)` (ARCHITECTURE §4.6). A camera request made elsewhere
 * is reconciled whenever this page appears and whenever the request token changes; [onOpenScanner] presents the
 * camera. System Back is left to the tab glue (Wallets), so it never discards a reviewed request or a signature.
 */
@Composable
fun ScanTabScreen(
    onOpenScanner: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanTabViewModel = hiltViewModel(),
    homeViewModel: ScanHomeViewModel = hiltViewModel(),
) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
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
    val scrollToTop: Flow<Unit> = remember(viewModel) { viewModel.reselections.map { } }
    AnimatedContent(
        targetState = stage,
        modifier = modifier,
        transitionSpec = { fadeIn(SignerMotion.contentSwap()) togetherWith fadeOut(SignerMotion.contentSwap()) },
        label = "scanStage",
    ) { shown ->
        when (shown) {
            ScanStage.Home -> ScanHomeScreen(viewModel = homeViewModel, onOpenScanner = openScanner, scrollToTop = scrollToTop)
            ScanStage.Review -> RequestReviewScreen(scrollToTop = scrollToTop)
            ScanStage.Signature -> SignatureResultScreen(scrollToTop = scrollToTop)
        }
    }
}
