package com.bittool.signer.feature.sign.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.scanning.QrScanPurpose
import com.bittool.signer.core.scanner.QrScannerScreen
import com.bittool.signer.core.ui.text.asString
import com.bittool.signer.feature.sign.common.CollectEvents

/**
 * The full-screen camera for signing requests (inventory row 47; iOS `QRScannerView` presented by `ScanHomeView`,
 * `Features/Sign/ScanTab.swift:144-150`): the shared [QrScannerScreen] with the signing title, hint and purpose, fed by
 * [ScannerViewModel] (iOS `ScanSession`). Closing (the cross, system Back) ends the engine scan session first; a
 * completed request closes it by itself, the review already rendered underneath.
 */
@Composable
fun SigningScannerScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: ScannerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    CollectEvents(viewModel.events) { event ->
        when (event) {
            ScannerEvent.Completed -> close()
        }
    }
    QrScannerScreen(
        title = stringResource(R.string.scan_request),
        hint = stringResource(R.string.point_the_camera_at_the_qr_code_in_your_other_wallet),
        purpose = QrScanPurpose.SigningRequest,
        progress = state.progress.toFloat(),
        status = state.status?.asString(),
        error = state.error?.asString(),
        onReset = viewModel::start,
        onClose = {
            viewModel.stop()
            close()
        },
        onScan = viewModel::receive,
        modifier = modifier,
    )
}
