package com.bittool.signer.core.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.bittool.signer.core.model.QrEncodingException
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.scanner.internal.QrWindowEffects
import com.bittool.signer.core.scanner.internal.hostWindow
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.bittool.signer.core.l10n.R as L10n

/**
 * A static or animated (multi-part BC-UR / BBQr) QR code on an always-white plate: port of iOS `QRCodeView`
 * (`Design/QRCodeView.swift:29-111`; scan-sign.md §15.1, design-system.md §5.12, §9.5, wallets-pairing.md §3.4).
 *
 * - Every frame is encoded by [ZxingQrCodeEncoder] (M up to 600 UTF-8 bytes, else L; ECI only for non-ASCII) and
 *   rendered at one pixel per module with CoreImage's one-module quiet zone, **all frames off the main thread before
 *   the first one is shown**, so parts never flash in. A grey spinner sits on the plate meanwhile. A frame that cannot
 *   be encoded is left out, as iOS `compactMap` does.
 * - [initial] (matrices prepared ahead for exactly these [frames], e.g. `SignerStore.preparedConnect`) is shown at
 *   once, so nothing changes during a navigation push (`QRCodeView.swift:41-51`, `ConnectView.swift:99-113`).
 * - Modules are drawn with nearest-neighbour sampling at an integer pixel size, centred: crisp, uniform modules at
 *   every density (the leftover pixels become extra white margin).
 * - Several frames: the part advances every [intervalMillis] (iOS 250 ms) while not paused and the screen is resumed
 *   (the code is under the privacy cover otherwise), also with animations removed, because the motion carries data.
 *   A new set of frames restarts at part 1; pause survives it, as on iOS. Below the plate: play/pause, a progress bar
 *   and the "i/n" counter (fixed width, LTR, tabular digits).
 * - TalkBack: "QR code" for one frame; "Animated QR code, part i of n" for several, updated (polite live region) only
 *   when the animation is paused, never four times a second (design-system.md §9.5, Ownbit improvement on iOS).
 * - While shown and resumed, the hosting window (the sheet's own window inside a bottom sheet) keeps the screen on
 *   and, when [maxBrightness], runs at full brightness so the other device's camera reads the code reliably; both
 *   are reference-counted and restored when the last code leaves. Android additions (iOS changes neither).
 *
 * Only public or already-protected data belongs here: frames stay in composition memory and are never cached across
 * screens.
 *
 * @param maxSide largest plate side (iOS callers: 250 backup, 280 address, 300 coin pairing, 320 activity,
 *   340 signature, 360 wallet pairing).
 */
@Composable
fun QrCodeView(
    frames: List<String>,
    modifier: Modifier = Modifier,
    maxSide: Dp = 320.dp,
    intervalMillis: Long = 250,
    initial: List<QrMatrix>? = null,
    maxBrightness: Boolean = true,
) {
    val immediate = remember(frames, initial) { QrFrames.prepared(frames, initial) }
    var rendered by remember { mutableStateOf<RenderedQr?>(null) }
    val current = immediate ?: rendered?.takeIf { it.frames == frames }
    val images = current?.images.orEmpty()
    val ready = images.isNotEmpty()
    val count = frames.size
    val animated = count > 1

    var index by remember(frames) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var describedPart by remember(frames) { mutableIntStateOf(1) }
    val imageCount by rememberUpdatedState(images.size)

    LaunchedEffect(frames, immediate) {
        if (immediate != null) {
            // Kept, so a caller that later drops its prepared matrices for the same frames shows no spinner.
            rendered = immediate
            return@LaunchedEffect
        }
        if (rendered?.frames == frames) return@LaunchedEffect
        val encoded = withContext(Dispatchers.Default) { QrFrames.render(frames) }
        rendered = RenderedQr(frames, encoded)
    }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(frames, ready, paused, resumed, intervalMillis) {
        if (!animated || !ready || paused || !resumed) return@LaunchedEffect
        while (true) {
            delay(intervalMillis)
            index = (index + 1) % imageCount.coerceAtLeast(1)
        }
    }

    QrDisplayWindowEffects(maxBrightness)

    val label = if (animated) {
        stringResource(L10n.string.animated_qr_code_part_x_of_x, describedPart, count)
    } else {
        stringResource(L10n.string.qr_code_2)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxSide)
                .fillMaxWidth()
                .aspectRatio(1f)
                .dropShadow(PlateShape, PlateShadow)
                .clip(PlateShape)
                .background(Color.White)
                .testTag(QrCodeViewTags.PLATE)
                .semantics {
                    if (ready) {
                        contentDescription = label
                        role = Role.Image
                        if (animated) liveRegion = LiveRegionMode.Polite
                    }
                }
                .padding(PlatePadding),
            contentAlignment = Alignment.Center,
        ) {
            val image = images.getOrNull(index) ?: images.firstOrNull()
            if (image != null) {
                QrImage(image)
            } else {
                CircularProgressIndicator(color = Color.Gray, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
        }
        if (animated) {
            QrPlaybackControls(
                index = index,
                count = count,
                paused = paused,
                onTogglePause = {
                    paused = !paused
                    if (paused) describedPart = index + 1
                },
            )
        }
    }
}

/** Test tags of [QrCodeView] (Android-only; iOS has no identifiers here). */
internal object QrCodeViewTags {
    const val PLATE = "qr.plate"
    const val PLAY_PAUSE = "qr.playPause"
    const val COUNTER = "qr.counter"
}

@Immutable
internal class RenderedQr(val frames: List<String>, val images: List<ImageBitmap>)

internal object QrFrames {
    private val encoder = ZxingQrCodeEncoder()

    /** [initial] as images when it was prepared for exactly these frames (`QRCodeView.swift:47`), else null. */
    fun prepared(frames: List<String>, initial: List<QrMatrix>?): RenderedQr? {
        if (initial == null || frames.isEmpty() || initial.size != frames.size) return null
        return RenderedQr(frames, initial.map(QrRendering::imageBitmap))
    }

    /** Encodes and renders every frame (CPU work: call off the main thread); frames that do not fit are left out. */
    fun render(frames: List<String>): List<ImageBitmap> = frames.mapNotNull { frame ->
        try {
            QrRendering.imageBitmap(encoder.encode(frame))
        } catch (e: QrEncodingException) {
            null
        }
    }
}

/** One pixel per module, scaled by an integer factor with nearest-neighbour sampling, centred. */
@Composable
private fun QrImage(image: ImageBitmap) {
    Canvas(Modifier.fillMaxSize()) {
        val pixels = image.width
        val side = min(size.width, size.height).toInt()
        val cell = side / pixels
        val drawn = if (cell >= 1) cell * pixels else side
        val left = ((size.width - drawn) / 2f).roundToInt()
        val top = ((size.height - drawn) / 2f).roundToInt()
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(pixels, image.height),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(drawn, drawn),
            filterQuality = FilterQuality.None,
        )
    }
}

/** `QRCodeView.swift:74-93`: bordered circle play/pause, progress (hidden from TalkBack), "i/n" counter. */
@Composable
private fun QrPlaybackControls(index: Int, count: Int, paused: Boolean, onTogglePause: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val counterStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = 12.sp,
        fontFeatureSettings = "tnum",
        textDirection = TextDirection.Ltr,
        textAlign = TextAlign.Center,
    )
    val widest = stringResource(L10n.string.x_x_2, count, count)
    val counterWidth = rememberCounterWidth(widest, counterStyle)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val toggleLabel = stringResource(if (paused) L10n.string.play_qr_animation else L10n.string.pause_qr_animation)
        FilledTonalIconButton(
            onClick = onTogglePause,
            modifier = Modifier
                .testTag(QrCodeViewTags.PLAY_PAUSE)
                .semantics { contentDescription = toggleLabel },
        ) {
            AnimatedContent(
                targetState = paused,
                transitionSpec = {
                    (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.6f))
                },
                label = "qrPlayPause",
            ) { isPaused ->
                Icon(
                    imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = { (index + 1).toFloat() / count },
            modifier = Modifier
                .width(140.dp)
                .height(4.dp)
                .clearAndSetSemantics { },
            color = colors.primary,
            trackColor = colors.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            text = stringResource(L10n.string.x_x_2, index + 1, count),
            style = counterStyle,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            // Readable by TalkBack as on iOS (not a live region, so it is never announced by itself).
            modifier = Modifier
                .width(counterWidth)
                .testTag(QrCodeViewTags.COUNTER),
        )
    }
}

/** The width of the widest counter value ("12/12"), so the centred row never shifts between parts. */
@Composable
private fun rememberCounterWidth(widest: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(widest, style, density) {
        with(density) { measurer.measure(widest, style, maxLines = 1, softWrap = false).size.width.toDp() }
    }
}

/**
 * Keeps the screen on and, when [maxBrightness], at full brightness while composed and resumed, on the window that
 * hosts this composition (activity, dialog or bottom sheet).
 */
@Composable
private fun QrDisplayWindowEffects(maxBrightness: Boolean) {
    val view = LocalView.current
    LifecycleResumeEffect(view, maxBrightness) {
        val window = view.hostWindow()
        window?.let { QrWindowEffects.acquire(it, maxBrightness) }
        onPauseOrDispose { window?.let { QrWindowEffects.release(it, maxBrightness) } }
    }
}

private val PlateShape = RoundedCornerShape(24.dp)
private val PlatePadding = 18.dp
private val PlateShadow = Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.08f), offset = DpOffset(0.dp, 6.dp))
