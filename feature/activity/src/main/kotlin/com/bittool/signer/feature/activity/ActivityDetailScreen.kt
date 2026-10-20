package com.bittool.signer.feature.activity

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bittool.signer.core.designsystem.component.FirstLineIcon
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.IconRowDividerInset
import com.bittool.signer.core.designsystem.component.NavRow
import com.bittool.signer.core.designsystem.component.StatusPill
import com.bittool.signer.core.designsystem.component.ValueRow
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.ui.address.HashText
import com.bittool.signer.core.ui.payload.PayloadDisclosure
import com.bittool.signer.core.ui.text.asString
import com.bittool.signer.core.ui.text.rememberReviewText

/** Test tags of the Signature page (iOS accessibility identifiers, activity-settings.md §2.8). */
object ActivityDetailTags {
    const val DECODED = "activity.decoded"
    const val REQUEST = "activity.request"
    const val SHOW_QR = "activity.showQR"
    const val EXPORT = "activity.export"
    const val SIGNATURE = "activity.signature"
    const val QR_DONE = "activity.qrDone"
    const val LIST = "activity.detail"
}

/** The Signature page of one receipt (iOS `ActivityDetailView`, inventory rows 58, 60, 61). */
@Composable
fun ActivityDetailScreen(
    onBack: () -> Unit,
    onOpenDecodedFields: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.detailEvents.collect { event ->
                when (event) {
                    ActivityDetailEvent.Close -> onBack()
                }
            }
        }
    }
    LifecycleStartEffect(viewModel) {
        viewModel.onAppear()
        onStopOrDispose { }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE), viewModel::onExport)
    val fileName = stringResource(R.string.activity_export_file_name)
    val back = rememberResumedAction<Unit> { onBack() }
    val openDecoded = rememberResumedAction<Unit> { onOpenDecodedFields(viewModel.receiptId) }
    ActivityDetailContent(
        state = state,
        onBack = { back(Unit) },
        onOpenDecodedFields = { openDecoded(Unit) },
        onShowQr = viewModel::onShowQr,
        onDismissQr = viewModel::onDismissQr,
        onExport = {
            // A device without a document picker (some work profiles, stripped builds) has nothing to launch.
            try {
                exporter.launch(fileName)
            } catch (_: ActivityNotFoundException) {
                viewModel.onExportUnavailable()
            }
        },
        modifier = modifier,
    )
}

/** Stateless Signature page (previews, screenshots and Compose tests). */
@Composable
internal fun ActivityDetailContent(
    state: ActivityDetailUiState,
    onBack: () -> Unit,
    onOpenDecodedFields: () -> Unit,
    onShowQr: () -> Unit,
    onDismissQr: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ActivityPage(title = stringResource(R.string.signature), onBack = onBack, modifier = modifier) { insets ->
        val receipt = state.receipt ?: return@ActivityPage
        DetailList(state, receipt, insets, onOpenDecodedFields, onShowQr, onExport)
    }
    if (state.showsQr) SignatureQrSheet(frames = state.frames, onDismiss = onDismissQr)
}

@Composable
private fun DetailList(
    state: ActivityDetailUiState,
    receipt: SigningReceipt,
    insets: PaddingValues,
    onOpenDecodedFields: () -> Unit,
    onShowQr: () -> Unit,
    onExport: () -> Unit,
) {
    val reviewText = rememberReviewText()
    val formats = rememberActivityDateFormats()
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .testTag(ActivityDetailTags.LIST),
        contentPadding = listContentPadding(insets),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        item(key = "header") { DetailHeader(receipt, reviewText) }
        item(key = "metadata") { Metadata(state, receipt, formats) }
        item(key = "fingerprint") {
            GroupedSection(
                modifier = SectionFrame,
                header = stringResource(R.string.request_fingerprint),
                footer = stringResource(R.string.bittool_signer_does_not_broadcast_whether_this),
            ) {
                item { HashText(receipt.requestHash, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) }
            }
        }
        receipt.requestJson?.let { json ->
            item(key = "input") { InputSection(state.decode, json, reviewText, onOpenDecodedFields) }
        }
        if (state.hasOutput) {
            item(key = "output") { OutputSection(state, onShowQr, onExport) }
        }
        if (!receipt.hasPayloads) {
            item(key = "retention") {
                ListFootnote(stringResource(R.string.the_request_and_signature_are_kept_for_the_x_most, SigningReceipt.PAYLOAD_RETENTION))
            }
        }
    }
}

/** Logo, title, summary and the result pill, centred on the page (iOS clear-background header row). */
@Composable
private fun DetailHeader(receipt: SigningReceipt, reviewText: ReviewText) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val resources = LocalResources.current
    Column(
        modifier = SectionFrame.padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReceiptLogo(receipt.chain, receipt.chainId, size = 56.dp)
        Text(
            text = receiptTitle(receipt.title, reviewText, resources),
            style = typography.title3.bold().contentDirection(),
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        receipt.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
            Text(summary, style = typography.subheadline.contentDirection(), color = colors.secondaryLabel, textAlign = TextAlign.Center)
        }
        StatusPill(text = resultText(receipt.resultType), icon = SignerIcons.VerifiedFilled, color = colors.success)
    }
}

@Composable
private fun Metadata(state: ActivityDetailUiState, receipt: SigningReceipt, formats: ActivityDateFormats) {
    val date = formats.dateTime(receipt.date)
    val network = receipt.networkName ?: receipt.chain.displayName
    val wallet = state.wallet?.text()
    val risk = when (state.risk) {
        RiskRow.High -> stringResource(R.string.activity_risk_high)
        RiskRow.WarningsShown -> stringResource(R.string.activity_risk_warnings_shown)
        null -> null
    }
    val labels = MetadataLabels(
        date = stringResource(R.string.date),
        network = stringResource(R.string.network),
        wallet = stringResource(R.string.wallet),
        fingerprint = stringResource(R.string.fingerprint),
        risk = stringResource(R.string.risk),
    )
    // Record values (network and wallet names, the fingerprint, formatted dates) keep their own direction.
    GroupedSection(SectionFrame) {
        item(key = "date") { ValueRow(labels.date, date, verbatim = true) }
        item(key = "network") { ValueRow(labels.network, network, verbatim = true) }
        if (wallet != null) item(key = "wallet") { ValueRow(labels.wallet, wallet, verbatim = true) }
        state.fingerprint?.let { fingerprint -> item(key = "fingerprint") { ValueRow(labels.fingerprint, fingerprint, verbatim = true) } }
        if (risk != null) item(key = "risk") { ValueRow(labels.risk, risk, verbatim = true) }
    }
}

private class MetadataLabels(val date: String, val network: String, val wallet: String, val fingerprint: String, val risk: String)

/**
 * "Input" (iOS `ActivityDetailView.input`): the request decoded again — "Decoding again…", the failure, or the
 * essentials and "All Decoded Fields" — and the stored request JSON behind "Request Payload".
 */
@Composable
private fun InputSection(decode: DecodeState, json: String, reviewText: ReviewText, onOpenDecodedFields: () -> Unit) {
    val resources = LocalResources.current
    val allFields = stringResource(R.string.all_decoded_fields)
    val requestPayload = stringResource(R.string.request_payload)
    val decoding = stringResource(R.string.decoding_again)
    val failure = (decode as? DecodeState.Failed)?.message?.asString()
    GroupedSection(
        modifier = SectionFrame,
        header = stringResource(R.string.input),
        footer = stringResource(R.string.the_exact_request_that_was_signed_it_is_decoded_again),
    ) {
        when (decode) {
            DecodeState.Decoding, DecodeState.NotStored -> item(key = "decoding") { DecodingRow(decoding) }
            is DecodeState.Failed -> item(key = "failed") { FailureRow(failure.orEmpty()) }
            is DecodeState.Decoded -> {
                decode.essentials.forEach { line ->
                    item(key = line.id) {
                        ValueRow(
                            label = reviewText.phrase(line.label),
                            value = if (line.id == "action") actionText(line.value, reviewText, resources) else line.value,
                            verbatim = true,
                        )
                    }
                }
                item(key = "decoded", dividerInset = IconRowDividerInset) {
                    NavRow(
                        title = allFields,
                        icon = SignerIcons.ListAlt,
                        onClick = onOpenDecodedFields,
                        modifier = Modifier.testTag(ActivityDetailTags.DECODED),
                    )
                }
            }
        }
        item(key = "payload") {
            PayloadDisclosure(
                title = requestPayload,
                text = json,
                tag = ActivityDetailTags.REQUEST,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** The action line in the interface language (the review screen's rule), the English when there is no entry. */
private fun actionText(english: String, reviewText: ReviewText, resources: android.content.res.Resources): String =
    if (english == TransactionReview.DEFAULT_TITLE) resources.getString(R.string.ui_review_request) else reviewText.phrase(english)

@Composable
private fun DecodingRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = SignerTheme.colors.secondaryLabel, strokeWidth = 2.dp)
        Text(text, style = SignerTheme.typography.body, color = SignerTheme.colors.secondaryLabel)
    }
}

/** The re-review failure (iOS `Label(decodeError, systemImage: "exclamationmark.triangle.fill")`, footnote, warning). */
@Composable
private fun FailureRow(text: String) {
    val style = SignerTheme.typography.footnote.contentDirection()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FirstLineIcon(SignerIcons.WarningFilled, SignerTheme.colors.warning, style, size = 16.dp)
        Text(text, style = style, color = SignerTheme.colors.warningOnTint)
    }
}

/** "Output" (iOS `ActivityDetailView.output`): the signature QR again, the file export and the raw signature text. */
@Composable
private fun OutputSection(state: ActivityDetailUiState, onShowQr: () -> Unit, onExport: () -> Unit) {
    val showQr = stringResource(R.string.show_qr_code)
    val export = stringResource(R.string.export_as_file)
    val signaturePayload = stringResource(R.string.signature_payload)
    val error = state.exportError?.asString()
    GroupedSection(
        modifier = SectionFrame,
        header = stringResource(R.string.output),
        footer = stringResource(R.string.the_signature_exactly_as_it_was_produced_showing_it),
    ) {
        if (state.frames.isNotEmpty()) {
            item(key = "qr", dividerInset = IconRowDividerInset) {
                NavRow(
                    title = showQr,
                    icon = SignerIcons.QrCode,
                    onClick = onShowQr,
                    showChevron = false,
                    modifier = Modifier.testTag(ActivityDetailTags.SHOW_QR),
                )
            }
        }
        if (state.responseText.isNotEmpty()) {
            item(key = "export") {
                NavRow(
                    title = export,
                    icon = SignerIcons.SaveFile,
                    onClick = onExport,
                    showChevron = false,
                    modifier = Modifier.testTag(ActivityDetailTags.EXPORT),
                )
            }
            item(key = "signature") {
                PayloadDisclosure(
                    title = signaturePayload,
                    text = state.responseText,
                    tag = ActivityDetailTags.SIGNATURE,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (error != null) {
            item(key = "exportError") {
                Text(
                    text = error,
                    style = SignerTheme.typography.footnote.contentDirection(),
                    color = SignerTheme.colors.dangerOnTint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** iOS `.plainText` content type of the exported signature. */
private const val EXPORT_MIME_TYPE = "text/plain"

private val SECTION_GAP = 20.dp
