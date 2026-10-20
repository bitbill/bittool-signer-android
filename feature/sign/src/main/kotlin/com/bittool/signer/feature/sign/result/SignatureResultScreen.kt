package com.bittool.signer.feature.sign.result

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.scanner.QrCodeView
import com.bittool.signer.core.ui.logo.ChainLogo
import com.bittool.signer.core.ui.logo.NetworkLogo
import com.bittool.signer.core.ui.logo.TokenIcon
import com.bittool.signer.core.ui.payload.PayloadDisclosure
import com.bittool.signer.core.ui.text.Callout
import com.bittool.signer.core.ui.text.localizedSummary
import com.bittool.signer.core.ui.text.localizedTitle
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.rememberLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The signature result with its file export (inventory rows 54–55): [SignatureResultContent] driven by
 * [SignatureResultViewModel], "Export as File" through the system document picker.
 */
@Composable
fun SignatureResultScreen(
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
    viewModel: SignatureResultViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SignatureResultViewModel.EXPORT_MIME_TYPE),
    ) { uri -> viewModel.export(uri) }
    SignatureResultContent(
        state = state,
        onExport = { exporter.launch(SignatureResultViewModel.EXPORT_FILE_NAME) },
        onDone = viewModel::done,
        modifier = modifier,
        scrollToTop = scrollToTop,
    )
}

/**
 * The result page (iOS `SignatureResultView`, scan-sign.md §15): "Signature Created" with a one-time bounce and success
 * haptic, the response QR (animated for several parts, max 340 dp), the request it answers (engine title and summary
 * verbatim), what the QR carries with "Export as File", the reminder that signing sends nothing, an export failure,
 * and "Done" pinned at the bottom (560 dp readable width). While the page fades out after Done it keeps its content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureResultContent(
    state: SignatureResultUiState,
    onExport: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
    scrollState: ScrollState = rememberScrollState(),
) {
    val shown = rememberLatest(state) { it.showsDetails || it.frames.isNotEmpty() }
    val haptics = rememberSignerHaptics()
    // iOS `.symbolEffect(.bounce, value: appeared)` and `.sensoryFeedback(.success, trigger: appeared)`: once, when
    // this signature first appears — not again after a rotation or a return to the tab.
    var appeared by rememberSaveable { mutableStateOf(false) }
    val bounce = remember { !appeared }
    LaunchedEffect(Unit) {
        if (!appeared) {
            appeared = true
            haptics.success()
        }
    }
    LaunchedEffect(scrollToTop, scrollState) { scrollToTop.collect { scrollState.animateScrollTo(0) } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection).testTag(SignTags.SIGNATURE),
        containerColor = SignerTheme.colors.page,
        topBar = {
            SignerTopAppBar(title = stringResource(R.string.signature), navigation = NavIcon.None, scrollBehavior = scrollBehavior)
        },
        bottomBar = {
            BottomActionBar(maxWidth = SignerMetrics.resultWidth, canScrollUnder = scrollState.canScrollForward) {
                PrimaryButton(
                    text = stringResource(R.string.done),
                    onClick = onDone,
                    icon = SignerIcons.Check,
                    modifier = Modifier.testTag(SignTags.SIGNATURE_DONE),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 20.dp)
                .readableWidth(SignerMetrics.resultWidth)
                .padding(horizontal = SignerMetrics.pagePadding),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ResultHeader(bounce)
            if (shown.frames.isNotEmpty()) QrCodeView(frames = shown.frames, maxSide = 340.dp)
            shown.review?.let { RequestSummary(it) }
            if (shown.showsDetails) ResultDetails(shown, onExport)
            Callout(text = stringResource(R.string.signing_doesnt_send_anything_your_other_wallet))
            state.exportError?.let { Callout(text = it, tone = Tone.Danger) }
        }
    }
}

@Composable
private fun ResultHeader(bounce: Boolean) {
    val colors = SignerTheme.colors
    val reduceMotion = SignerTheme.reduceMotion
    val scale = remember { Animatable(1f) }
    if (bounce && !reduceMotion) {
        LaunchedEffect(Unit) {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = SignerMotion.SYMBOL_BOUNCE_MILLIS
                    1f at 0
                    SignerMotion.SYMBOL_BOUNCE_SCALE at SignerMotion.SYMBOL_BOUNCE_MILLIS / 2
                    1f at SignerMotion.SYMBOL_BOUNCE_MILLIS
                },
            )
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = SignerIcons.VerifiedFilled,
            contentDescription = null,
            tint = colors.success,
            modifier = Modifier
                .size(52.dp)
                .clearAndSetSemantics {}
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
        Text(
            text = stringResource(R.string.signature_created),
            style = SignerTheme.typography.title2.bold(),
            color = colors.label,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }.testTag(SignTags.SIGNATURE_TITLE),
        )
        Text(
            text = stringResource(R.string.scan_this_code_with_the_wallet_that_created_the_request),
            style = SignerTheme.typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** The request this signature answers (`SignatureResultView.swift:30-47`): icon at 32, engine title and summary verbatim. */
@Composable
private fun RequestSummary(review: TransactionReview) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    SignerCard(Modifier.semantics(mergeDescendants = true) {}, padding = PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clearAndSetSemantics {}) {
                val asset = review.asset
                val brand = review.network?.brand
                when {
                    asset != null -> TokenIcon(asset.icon, asset.symbol, size = 32.dp)
                    brand != null -> NetworkLogo(brand, size = 32.dp)
                    else -> ChainLogo(review.chain, size = 32.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(review.title, style = typography.subheadline.semibold().contentDirection(), color = colors.label)
                if (review.summary.isNotEmpty()) {
                    Text(
                        text = review.summary,
                        style = typography.footnote.contentDirection(),
                        color = colors.secondaryLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** What the QR carries, for reference, and the file export (`SignatureResultView.swift:76-97`). */
@Composable
private fun ResultDetails(state: SignatureResultUiState, onExport: () -> Unit) {
    val colors = SignerTheme.colors
    SignerCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.payload?.let { payload ->
                DetailLine(stringResource(R.string.format), payload.localizedSummary(), monospaced = false)
                PayloadDisclosure(
                    title = payload.localizedTitle(),
                    text = payload.text,
                    notes = if (payload.isPsbt) listOf(stringResource(R.string.sign_psbt_payload_note)) else emptyList(),
                    tag = SignTags.SIGNATURE_PAYLOAD,
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.opaqueSeparator))
            }
            val enabled = state.text.isNotEmpty()
            val tint = if (enabled) colors.accent else colors.tertiaryLabel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SignerMetrics.minTouchTarget)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onExport)
                    .testTag(SignTags.SIGNATURE_EXPORT),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SignerIcons.Export, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.export_as_file), style = SignerTheme.typography.subheadline.semibold(), color = tint)
            }
        }
    }
}
