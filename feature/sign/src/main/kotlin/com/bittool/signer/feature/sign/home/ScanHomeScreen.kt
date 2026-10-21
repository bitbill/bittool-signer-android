package com.bittool.signer.feature.sign.home

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StepRow
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.pressScale
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerRadius
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.feature.sign.common.RevealedErrorCallout
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.symbolSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Scan home with its paste sheet and file picker (inventory rows 46, 49, 50): [viewModel] holds the state;
 * [onOpenScanner] presents the camera ([ScanHomeViewModel.onOpenScanner] has cleared the error). [scrollToTop] fires
 * when the selected Scan tab is tapped again.
 */
@Composable
fun ScanHomeScreen(
    viewModel: ScanHomeViewModel,
    onOpenScanner: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // iOS allows [.data, .plainText, .json]; `.data` matches any file, PSBTs usually arrive as octet-stream.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.importFile(uri) }
    val context = LocalContext.current
    ScanHomeContent(
        state = state,
        onScan = onOpenScanner,
        onPaste = viewModel::openPaste,
        onImportFile = { picker.launch(arrayOf("*/*")) },
        modifier = modifier,
        scrollToTop = scrollToTop,
    )
    val paste = state.paste
    if (paste != null) {
        PasteRequestSheet(
            state = paste,
            text = viewModel.pasteText,
            canSubmit = viewModel.canSubmitPaste(paste),
            onDismiss = viewModel::dismissPaste,
            onPasteFromClipboard = { viewModel.pasteClipboard(clipboardText(context)) },
            onSubmit = viewModel::submitPaste,
        )
    }
}

/** iOS `UIPasteboard.general.string`: the first clip item's plain text, or null (a URI or intent is not text). */
private fun clipboardText(context: Context): CharSequence? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
    return clip.getItemAt(0).text
}

/**
 * Scan home (iOS `ScanHomeView`, `Features/Sign/ScanTab.swift:108-223`): the large title "Scan"; the scan card (one
 * button, `signing.scan`, read as "Scan signing request"); "Paste Request" and "Import File" (disabled while an import
 * runs, Import File showing its progress); the import error, scrolled into view when it appears (it is laid out below
 * the tiles, often under the fold); "How signing works".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHomeContent(
    state: ScanHomeUiState,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onImportFile: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = SignerTheme.colors
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    LaunchedEffect(scrollToTop) {
        scrollToTop.collect {
            // A programmatic scroll does not reach the bar's nested scroll: expand the large title as well.
            scrollBehavior.state.heightOffset = 0f
            scope.launch { scrollState.animateScrollTo(0) }
        }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection).testTag(SignTags.HOME),
        containerColor = colors.page,
        topBar = {
            SignerTopAppBar(title = stringResource(R.string.scan), navigation = NavIcon.None, large = true, scrollBehavior = scrollBehavior)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)
                .readableWidth()
                .padding(horizontal = SignerMetrics.pagePadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ScanCard(onClick = onScan)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionTile(
                    title = stringResource(R.string.paste_request),
                    icon = SignerIcons.Paste,
                    tag = SignTags.PASTE,
                    enabled = !state.busy,
                    onClick = onPaste,
                    modifier = Modifier.weight(1f),
                )
                OptionTile(
                    title = stringResource(R.string.import_file),
                    icon = SignerIcons.NoteAdd,
                    tag = SignTags.FILE,
                    enabled = !state.busy,
                    busy = state.busy,
                    onClick = onImportFile,
                    modifier = Modifier.weight(1f),
                )
            }
            state.error?.let { RevealedErrorCallout(it) }
            HowSigningWorks()
        }
    }
}

@Composable
private fun ScanCard(onClick: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val label = stringResource(R.string.scan_signing_request)
    SignerCard(
        // One button for the whole card, read by its iOS accessibility label only.
        modifier = Modifier.semantics { contentDescription = label }.testTag(SignTags.SCAN),
        padding = PaddingValues(20.dp),
        radius = SignerRadius.summary,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ScanGlyphTile(Modifier.padding(top = 8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.scan_a_signing_request),
                    style = typography.title3.semibold(),
                    color = colors.label,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.show_the_unsigned_transaction_or_message_as_a_qr_code),
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
            // Drawn like a button but part of the card, so the whole card stays a single target.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accent, SignerShapes.capsule)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SignerIcons.Camera, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(symbolSize(20.dp, typography.body)))
                Text(stringResource(R.string.open_camera), style = typography.body.semibold(), color = colors.onAccent, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The 128 dp viewfinder tile with the repeating symbol pulse (none when animations are removed). */
@Composable
private fun ScanGlyphTile(modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val reduceMotion = SignerTheme.reduceMotion
    val alpha = if (reduceMotion) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "scanPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "scanPulseAlpha",
        )
    }
    Box(
        modifier = modifier.size(128.dp).background(colors.accent.copy(alpha = 0.12f), RoundedCornerShape(30.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = SignerIcons.QrCodeScanner,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(62.dp).graphicsLayer { this.alpha = alpha?.value ?: 1f },
        )
    }
}

/**
 * A tile under the scan card (iOS `optionTile`): the symbol over the label, top-aligned so that both tiles keep their
 * symbols on one line when only one label wraps (large text, longer translations); labels break by phrase and balance
 * their lines (no single glyph left on a line of its own in Japanese or Korean). [busy]: the file import this tile
 * started is running — its symbol becomes a spinner and TalkBack hears "Reading request…" (the picker has closed, the
 * engine is still reading: DECISIONS §5 "still show progress"); the tile stays at full strength while the other dims.
 */
@Composable
private fun OptionTile(
    title: String,
    icon: ImageVector,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val interaction = remember { MutableInteractionSource() }
    val reading = stringResource(R.string.sign_reading_request)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .pressScale(interaction)
            .then(if (busy) Modifier.semantics { stateDescription = reading } else Modifier)
            .testTag(tag),
        enabled = enabled,
        shape = SignerShapes.tile,
        color = colors.card,
        contentColor = colors.label,
        interactionSource = interaction,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 18.dp).alpha(if (enabled || busy) 1f else 0.4f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
        ) {
            val symbol = symbolSize(26.dp, typography.title2)
            Box(Modifier.size(symbol), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(symbol * 0.8f), color = colors.accent, strokeWidth = 2.5.dp)
                } else {
                    Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(symbol))
                }
            }
            Text(
                text = title,
                style = typography.subheadline.semibold().copy(lineBreak = LineBreak.Heading),
                color = colors.label,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HowSigningWorks() {
    SignerCard(padding = PaddingValues(18.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.how_signing_works),
                style = SignerTheme.typography.headline,
                color = SignerTheme.colors.label,
                modifier = Modifier.semantics { heading() },
            )
            StepRow(1, stringResource(R.string.scan), stringResource(R.string.your_watch_only_wallet_shows_the_unsigned_request_as_a), SignerIcons.QrCodeScanner)
            StepRow(2, stringResource(R.string.review), stringResource(R.string.bittool_signer_decodes_it_and_finds_the_matching_wallet), SignerIcons.ManageSearch)
            StepRow(3, stringResource(R.string.authorize), stringResource(R.string.enter_that_wallets_password_to_sign), SignerIcons.Lock)
            StepRow(4, stringResource(R.string.return_text), stringResource(R.string.scan_the_signature_back_your_other_wallet_broadcasts_it), SignerIcons.Undo)
        }
    }
}
