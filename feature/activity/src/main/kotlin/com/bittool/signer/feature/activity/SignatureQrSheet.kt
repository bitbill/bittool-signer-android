package com.bittool.signer.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.scanner.QrCodeView

/**
 * The stored signature QR again, for a wallet that did not finish scanning it (iOS `SignatureQRSheet`,
 * `ActivityTab.swift:307-330`): "Signature" with "Done", the (animated) code at up to 320 dp and the reminder that
 * scanning it again signs nothing new. Public data, so the sheet inherits the activity's window policy; the code keeps
 * the screen on while shown (`QrCodeView`). It stays open across a background visit: the privacy cover hides it.
 */
@Composable
internal fun SignatureQrSheet(frames: List<String>, onDismiss: () -> Unit) {
    SignerBottomSheet(onDismissRequest = onDismiss, testTag = "activity.qrSheet") {
        val sheet = this
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.signature),
                style = SignerTheme.typography.headline,
                color = SignerTheme.colors.label,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 96.dp)
                    .semantics { heading() },
            )
            TextAction(
                text = stringResource(R.string.done),
                onClick = { sheet.dismiss() },
                emphasized = true,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .testTag(ActivityDetailTags.QR_DONE),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .readableWidth(SignerMetrics.narrowWidth)
                .padding(SignerMetrics.pagePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QrCodeView(frames = frames, maxSide = 320.dp, modifier = Modifier.padding(top = 8.dp))
            Callout(stringResource(R.string.this_is_the_signature_that_was_already_created_scanning))
        }
    }
}
