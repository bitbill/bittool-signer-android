package com.bittool.signer.core.scanner.scan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.scanner.CameraPermissionStatus
import com.bittool.signer.core.l10n.R as L10n

/** Accessibility identifiers of the scanner (iOS `accessibilityIdentifier`s, scan-sign.md §20, plus Android ones). */
internal object ScannerTags {
    const val PROTOCOL_ERROR = "scanner.protocolError"
    const val RESET = "scanner.reset"
    const val PROGRESS = "scanner.progress"
    const val STATUS = "scanner.status"
    const val CAMERA_ERROR = "scanner.cameraError"

    /** Android-only. */
    const val CLOSE = "scanner.close"
    const val TORCH = "scanner.torch"
    const val OPEN_SETTINGS = "scanner.openSettings"
    const val PANEL = "scanner.panel"
}

/** What the scanner chrome shows; resolved strings only (the caller resolves at draw time). */
@Immutable
internal data class ScannerChrome(
    val title: String,
    val hint: String,
    val progress: Float?,
    val status: String?,
    val error: String?,
    val permission: CameraPermissionStatus,
    val cameraMessage: String?,
    val torchAvailable: Boolean,
    val torchOn: Boolean,
    val animationsEnabled: Boolean,
)

/**
 * The stateless scanner page (iOS `QRScannerView.body`, `QRScannerView.swift:26-126`): black page, [camera] (the
 * caller composes it only when authorised), the viewfinder overlay, the top bar and the bottom status panel. The
 * overlay spans the whole window (iOS `ignoresSafeArea`); the chrome sits inside the safe-drawing insets with 20 dp
 * horizontal and 12 dp vertical padding.
 *
 * Always dark, in this subtree only (iOS `.environment(\.colorScheme, .dark)`, `QRScannerView.swift:48`): the chrome
 * and [camera] draw with the dark [SignerTheme] palette (`mint`, `ink`, `warning`, the labels, `scannerScrim`), its
 * type scale and [SignerIcons], whatever the app's appearance. The system bars are the scanner screen's business.
 */
@Composable
internal fun QrScannerContent(
    chrome: ScannerChrome,
    onToggleTorch: () -> Unit,
    onOpenSettings: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    camera: @Composable () -> Unit = {},
) {
    SignerTheme(darkTheme = true, applySystemBarAppearance = false) {
        ScannerPage(chrome, onToggleTorch, onOpenSettings, onReset, onClose, modifier, camera)
    }
}

@Composable
private fun ScannerPage(
    chrome: ScannerChrome,
    onToggleTorch: () -> Unit,
    onOpenSettings: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    camera: @Composable () -> Unit,
) {
    val authorized = chrome.permission == CameraPermissionStatus.Granted
    // The window keeps clear of the top bar and the status panel, whatever their height (large text, long messages).
    val clearance = remember { ViewfinderClearance() }
    // iOS `Color.black.ignoresSafeArea()`: the backdrop behind the camera image, not a surface of the palette.
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (authorized) camera()
        ViewfinderOverlay(
            progress = chrome.progress ?: 0f,
            active = authorized,
            animationsEnabled = chrome.animationsEnabled,
            modifier = Modifier.fillMaxSize(),
            clearance = clearance,
        )
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScannerTopBar(
                title = chrome.title,
                torchAvailable = chrome.torchAvailable,
                torchOn = chrome.torchOn,
                onToggleTorch = onToggleTorch,
                onClose = onClose,
                modifier = Modifier.onGloballyPositioned { clearance.top = it.boundsInRoot().bottom },
            )
            Spacer(Modifier.weight(1f))
            StatusPanel(
                chrome,
                onOpenSettings,
                onReset,
                Modifier.onGloballyPositioned { clearance.bottom = it.boundsInRoot().top },
            )
        }
    }
}

/** `QRScannerView.swift:63-83`: Close · title · torch (invisible but keeping its space when unavailable). */
@Composable
private fun ScannerTopBar(
    title: String,
    torchAvailable: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val closeLabel = stringResource(L10n.string.close)
        GlassCircleButton(
            onClick = onClose,
            modifier = Modifier
                .testTag(ScannerTags.CLOSE)
                .semantics { contentDescription = closeLabel },
        ) {
            Icon(SignerIcons.Close, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Text(
            text = title,
            style = SignerTheme.typography.headline,
            color = SignerTheme.colors.label,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { heading() },
        )
        val torchLabel = stringResource(if (torchOn) L10n.string.turn_off_flashlight else L10n.string.turn_on_flashlight)
        GlassCircleButton(
            onClick = onToggleTorch,
            enabled = torchAvailable,
            modifier = Modifier
                .alpha(if (torchAvailable) 1f else 0f)
                .testTag(ScannerTags.TORCH)
                .then(
                    if (torchAvailable) {
                        Modifier.semantics { contentDescription = torchLabel }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ),
        ) {
            Icon(
                imageVector = if (torchOn) SignerIcons.FlashlightOn else SignerIcons.FlashlightOff,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A 48 dp translucent circle (iOS 44 pt glass circle; 48 dp is the Android minimum target). */
@Composable
private fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val label = SignerTheme.colors.label
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = GlassMaterial,
            contentColor = label,
            disabledContainerColor = GlassMaterial,
            disabledContentColor = label,
        ),
        content = content,
    )
}

private enum class PanelKind { CameraDenied, SessionError, Reading }

/** The panel's state; the error text travels with it so a fading-out error keeps its words. */
private data class PanelTarget(val kind: PanelKind, val error: String?)

/**
 * `QRScannerView.swift:85-126`: the first state that applies — camera access needed, a session error with
 * "Scan Again", or the reading state (progress while `0 < progress < 1`, the status or the hint, then any camera
 * problem). Content changes cross-fade and the panel resizes with SwiftUI's `.smooth(0.2)`. When the text does not
 * fit under the top bar (large font sizes, landscape), the panel scrolls instead of pushing its button off-screen.
 */
@Composable
private fun StatusPanel(
    chrome: ScannerChrome,
    onOpenSettings: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = when {
        chrome.permission == CameraPermissionStatus.Denied ||
            chrome.permission == CameraPermissionStatus.PermanentlyDenied -> PanelKind.CameraDenied
        chrome.error != null -> PanelKind.SessionError
        else -> PanelKind.Reading
    }
    Box(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .testTag(ScannerTags.PANEL)
            .background(SignerTheme.colors.raised.copy(alpha = PANEL_ALPHA), PanelShape)
            .animateContentSize(spring(dampingRatio = 1f, stiffness = 987f))
            .clip(PanelShape)
            .verticalScroll(rememberScrollState()),
    ) {
        AnimatedContent(
            targetState = PanelTarget(kind, chrome.error),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            contentAlignment = Alignment.Center,
            contentKey = { it.kind },
            label = "scannerPanel",
        ) { shown ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (shown.kind) {
                    PanelKind.CameraDenied -> CameraDeniedPanel(onOpenSettings)
                    PanelKind.SessionError -> SessionErrorPanel(shown.error.orEmpty(), onReset)
                    PanelKind.Reading -> ReadingPanel(chrome)
                }
            }
        }
    }
}

@Composable
private fun CameraDeniedPanel(onOpenSettings: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Icon(
        SignerIcons.Camera,
        contentDescription = null,
        tint = colors.secondaryLabel,
        modifier = Modifier.size(30.dp),
    )
    Text(
        text = stringResource(L10n.string.camera_access_needed),
        style = typography.headline,
        color = colors.label,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(L10n.string.allow_camera_access_in_settings_to_scan_qr_codes_or),
        style = typography.subheadline,
        color = colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
    MintButton(
        text = stringResource(L10n.string.open_settings),
        onClick = onOpenSettings,
        modifier = Modifier.testTag(ScannerTags.OPEN_SETTINGS),
    )
}

@Composable
private fun SessionErrorPanel(error: String, onReset: () -> Unit) {
    val colors = SignerTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .testTag(ScannerTags.PROTOCOL_ERROR)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            SignerIcons.WarningFilled,
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Text(
            text = error,
            style = SignerTheme.typography.subheadline,
            color = colors.warning,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
    MintButton(
        text = stringResource(L10n.string.scan_again),
        onClick = onReset,
        modifier = Modifier.testTag(ScannerTags.RESET),
    )
}

@Composable
private fun ReadingPanel(chrome: ScannerChrome) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val progress = chrome.progress ?: 0f
    if (progress > 0f && progress < 1f) {
        val bar = remember { Animatable(progress) }
        LaunchedEffect(progress) {
            if (progress < bar.value) bar.snapTo(progress) else bar.animateTo(progress, spring(dampingRatio = 1f, stiffness = 987f))
        }
        LinearProgressIndicator(
            progress = { bar.value },
            modifier = Modifier
                .widthIn(max = 220.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clearAndSetSemantics { },
            color = colors.mint,
            trackColor = colors.fill,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            text = stringResource(L10n.string.x_2, (progress * 100).toInt()),
            style = typography.caption.monospacedDigit(),
            color = colors.secondaryLabel,
            modifier = Modifier.testTag(ScannerTags.PROGRESS),
        )
    }
    Text(
        text = chrome.status ?: chrome.hint,
        style = typography.subheadline,
        color = colors.label,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .testTag(ScannerTags.STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    chrome.cameraMessage?.let { message ->
        Text(
            text = message,
            style = typography.footnote,
            color = colors.warning,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag(ScannerTags.CAMERA_ERROR)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** iOS `.borderedProminent` tinted `Brand.mint` with `Brand.ink` text. */
@Composable
private fun MintButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = colors.mint, contentColor = colors.ink),
    ) {
        Text(text, style = SignerTheme.typography.subheadline.semibold())
    }
}

/**
 * Android renditions of iOS `.ultraThinMaterial` over the dimmed camera, on the scanner's always-dark page. A material
 * is a translucent blur rather than a palette colour (design-system.md §2.3), and Android has no blur behind a view:
 *
 * - The status panel is the dark `raised` grey (`#2C2C2E`) at [PANEL_ALPHA]: a visible surface on the 80 % dim of a
 *   denied camera (black at 62 % was the same black as its surround, QA sign-09) that still lets the camera image show
 *   through faintly, with white text readable on any image behind it.
 * - The glass circles of the top bar are white at 16 %.
 */
private const val PANEL_ALPHA = 0.86f
private val GlassMaterial = Color.White.copy(alpha = 0.16f)

private val PanelShape = RoundedCornerShape(22.dp)
