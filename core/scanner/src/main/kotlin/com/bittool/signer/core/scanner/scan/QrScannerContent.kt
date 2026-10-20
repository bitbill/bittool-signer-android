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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bittool.signer.core.scanner.CameraPermissionStatus
import com.bittool.signer.core.scanner.internal.ScannerPalette
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
    val authorized = chrome.permission == CameraPermissionStatus.Granted
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (authorized) camera()
        ViewfinderOverlay(
            progress = chrome.progress ?: 0f,
            active = authorized,
            animationsEnabled = chrome.animationsEnabled,
            modifier = Modifier.fillMaxSize(),
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
            )
            Spacer(Modifier.weight(1f))
            StatusPanel(chrome, onOpenSettings, onReset)
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
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val closeLabel = stringResource(L10n.string.close)
        GlassCircleButton(
            onClick = onClose,
            modifier = Modifier
                .testTag(ScannerTags.CLOSE)
                .semantics { contentDescription = closeLabel },
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Text(
            text = title,
            style = ScannerText.Headline,
            color = Color.White,
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
                imageVector = if (torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
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
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = ScannerPalette.Glass,
            contentColor = Color.White,
            disabledContainerColor = ScannerPalette.Glass,
            disabledContentColor = Color.White,
        ),
        content = content,
    )
}

private enum class PanelKind { CameraDenied, SessionError, Reading }

/**
 * `QRScannerView.swift:85-126`: the first state that applies — camera access needed, a session error with
 * "Scan Again", or the reading state (progress while `0 < progress < 1`, the status or the hint, then any camera
 * problem). Content changes cross-fade and the panel resizes with SwiftUI's `.smooth(0.2)`.
 */
@Composable
private fun StatusPanel(chrome: ScannerChrome, onOpenSettings: () -> Unit, onReset: () -> Unit) {
    val kind = when {
        chrome.permission == CameraPermissionStatus.Denied ||
            chrome.permission == CameraPermissionStatus.PermanentlyDenied -> PanelKind.CameraDenied
        chrome.error != null -> PanelKind.SessionError
        else -> PanelKind.Reading
    }
    Box(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .background(ScannerPalette.Panel, PanelShape)
            .animateContentSize(spring(dampingRatio = 1f, stiffness = 987f)),
    ) {
        AnimatedContent(
            targetState = kind,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            contentAlignment = Alignment.Center,
            label = "scannerPanel",
        ) { shown ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (shown) {
                    PanelKind.CameraDenied -> CameraDeniedPanel(onOpenSettings)
                    PanelKind.SessionError -> SessionErrorPanel(chrome.error.orEmpty(), onReset)
                    PanelKind.Reading -> ReadingPanel(chrome)
                }
            }
        }
    }
}

@Composable
private fun CameraDeniedPanel(onOpenSettings: () -> Unit) {
    Icon(
        Icons.Rounded.PhotoCamera,
        contentDescription = null,
        tint = ScannerPalette.Secondary,
        modifier = Modifier.size(30.dp),
    )
    Text(
        text = stringResource(L10n.string.camera_access_needed),
        style = ScannerText.Headline,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(L10n.string.allow_camera_access_in_settings_to_scan_qr_codes_or),
        style = ScannerText.Subheadline,
        color = ScannerPalette.Secondary,
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .testTag(ScannerTags.PROTOCOL_ERROR)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            Icons.Rounded.Warning,
            contentDescription = null,
            tint = ScannerPalette.Orange,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Text(
            text = error,
            style = ScannerText.Subheadline,
            color = ScannerPalette.Orange,
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
            color = ScannerPalette.Mint,
            trackColor = Color.White.copy(alpha = 0.18f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            text = stringResource(L10n.string.x_2, (progress * 100).toInt()),
            style = ScannerText.Caption,
            color = ScannerPalette.Secondary,
            modifier = Modifier.testTag(ScannerTags.PROGRESS),
        )
    }
    Text(
        text = chrome.status ?: chrome.hint,
        style = ScannerText.Subheadline,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .testTag(ScannerTags.STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    chrome.cameraMessage?.let { message ->
        Text(
            text = message,
            style = ScannerText.Footnote,
            color = ScannerPalette.Orange,
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
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = ScannerPalette.Mint, contentColor = ScannerPalette.Ink),
    ) {
        Text(text, style = ScannerText.Subheadline.copy(fontWeight = FontWeight.SemiBold))
    }
}

/**
 * iOS text styles at the default size (scan-sign.md §19), on the app's body font, scaled with the user's font size.
 */
private object ScannerText {
    private val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    private val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    private val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    private val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")

    val Headline: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge.merge(headline)
    val Subheadline: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge.merge(subheadline)
    val Footnote: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge.merge(footnote)
    val Caption: TextStyle @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge.merge(caption)
}

private val PanelShape = RoundedCornerShape(22.dp)
