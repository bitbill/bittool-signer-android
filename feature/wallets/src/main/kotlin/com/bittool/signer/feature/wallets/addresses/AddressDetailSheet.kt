package com.bittool.signer.feature.wallets.addresses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.scanner.QrCodeView
import com.bittool.signer.core.ui.address.CopyableAddress
import com.bittool.signer.feature.wallets.common.QrBrightnessCorner

/**
 * One receive address as a QR code (inventory row 38, iOS `AddressDetailSheet`, `AddressesView.swift:170-201`): a full
 * height sheet with its own title bar and "Done", the bare address as a QR code (EC M, no URI scheme, 280 dp), the
 * copyable address with its derivation path, and "Address only. No public key or derivation path is included in this
 * QR code.". The sheet window keeps the screen on while the code is shown (`QrCodeView`).
 */
@Composable
internal fun AddressDetailSheet(account: PublicAccount, title: String, onDismiss: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    SignerBottomSheet(onDismissRequest = onDismiss, testTag = "address.sheet") {
        val sheet = this
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = SignerMetrics.pagePadding, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = typography.headline,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextAction(text = stringResource(R.string.done), onClick = { sheet.dismiss() }, emphasized = true, modifier = Modifier.testTag("address.done"))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(SignerMetrics.pagePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.readableWidth(SignerMetrics.narrowWidth), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                QrBrightnessCorner(maxSide = QR_SIDE, modifier = Modifier.padding(top = 8.dp)) { bright ->
                    QrCodeView(frames = listOf(account.address), maxSide = QR_SIDE, maxBrightness = bright, modifier = Modifier.testTag("address.qr"))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SignerShapes.card)
                        .background(colors.card)
                        .padding(SignerMetrics.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CopyableAddress(account.address, style = typography.body)
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.opaqueSeparator))
                    DetailLine(label = stringResource(R.string.derivation_path), value = account.path)
                }
                Callout(stringResource(R.string.address_only_no_public_key_or_derivation_path_is))
            }
        }
    }
}

private val QR_SIDE = 280.dp
