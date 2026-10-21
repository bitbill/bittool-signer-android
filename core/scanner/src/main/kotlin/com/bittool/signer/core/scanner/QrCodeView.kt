package com.bittool.signer.core.scanner

import androidx.annotation.VisibleForTesting
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.model.QrEncodingException
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.scanner.internal.QrWindowEffects
import com.bittool.signer.core.scanner.internal.hostWindow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * - An animated code's encoded frames and the part it reached are kept by its screen ([QrFrameCache]), so returning to
 *   it after a tab switch, a push or a configuration change neither shows the spinner nor restarts at part 1 (iOS
 *   `@State` survives `TabView` switches). They are forgotten when the code leaves a resumed screen. A one-frame code is
 *   never kept after it leaves. Pause is saved with the screen, as on iOS.
 * - TalkBack: "QR code" for one frame; "Animated QR code, part i of n" for several, updated (polite live region) only
 *   when the animation is paused, never four times a second (design-system.md §9.5, Ownbit improvement on iOS).
 * - Keep-screen-on only while an animated code plays (encoded, not paused, screen resumed), on the hosting window (the
 *   sheet's own window inside a bottom sheet): Android polish for a code that must stay in view until the other wallet
 *   has read every part (ARCHITECTURE §1.12, design-system.md §9.5). Never for a one-frame code, which includes the
 *   recovery-phrase QR: iOS never disables its idle timer, and the system's auto-lock is what conceals an unattended
 *   code (the activity pauses) and starts the App Lock clock (QA audit A02). Brightness is left alone unless the caller
 *   opts in with [maxBrightness]: iOS never changes it, and a brighter screen also makes a recovery-phrase code easier
 *   to photograph over a shoulder. Both requests are reference-counted per window and restored when the last code
 *   leaves.
 *
 * Only public or already-protected data belongs here: frames stay in composition memory (animated ones also in their
 * screen's [QrFrameCache]) and are never kept across screens.
 *
 * @param maxSide largest plate side (iOS callers: 250 backup, 280 address, 300 coin pairing, 320 activity,
 *   340 signature, 360 wallet pairing).
 * @param intervalMillis time per part of an animated code (iOS 250 ms); values below one frame (16 ms) are raised to
 *   it, so a zero or negative interval can never spin the main thread.
 * @param maxBrightness true to run the window at full brightness while the code is shown (opt-in, Android-only; for
 *   example the dense Wallet Level animated code, wallets-pairing.md §13.5). Default false, as on iOS.
 */
@Composable
fun QrCodeView(
    frames: List<String>,
    modifier: Modifier = Modifier,
    maxSide: Dp = 320.dp,
    intervalMillis: Long = 250,
    initial: List<QrMatrix>? = null,
    maxBrightness: Boolean = false,
) {
    val count = frames.size
    val animated = count > 1
    // Only animated codes are kept by their screen (A13): a one-frame code may be the recovery phrase.
    val cache = if (animated) QrFrameCache.forCurrentScreen() else null
    val immediate = remember(frames, initial) { QrFrames.prepared(frames, initial) }
    val parked = remember(frames, cache) { cache?.ready(frames) }
    var rendered by remember { mutableStateOf<RenderedQr?>(null) }
    val current = immediate ?: rendered?.takeIf { it.frames == frames } ?: parked
    val images = current?.images.orEmpty()
    val ready = images.isNotEmpty()

    var index by remember(frames, cache) { mutableIntStateOf(cache?.index(frames) ?: 0) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var describedPart by remember(frames, cache) { mutableIntStateOf(index + 1) }
    val imageCount by rememberUpdatedState(images.size)

    LaunchedEffect(frames, immediate, cache) {
        if (immediate != null) {
            // Kept, so a caller that later drops its prepared matrices for the same frames shows no spinner.
            rendered = immediate
            return@LaunchedEffect
        }
        if (rendered?.frames == frames) return@LaunchedEffect
        val encoded = cache?.render(frames) ?: withContext(Dispatchers.Default) { QrFrames.render(frames) }
        rendered = RenderedQr(frames, encoded)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    if (cache != null) {
        val owner by rememberUpdatedState(lifecycleOwner)
        DisposableEffect(cache, frames) {
            onDispose {
                // Below RESUMED the code leaves with its screen (tab switch, push, background, configuration change)
                // and is kept for its return; on a resumed screen the code itself went away and is forgotten.
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    cache.drop(frames)
                } else {
                    cache.park(frames, index)
                }
            }
        }
    }

    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val interval = intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
    LaunchedEffect(frames, ready, paused, resumed, interval) {
        if (!animated || !ready || paused || !resumed) return@LaunchedEffect
        while (true) {
            delay(interval)
            index = (index + 1) % imageCount.coerceAtLeast(1)
        }
    }

    QrDisplayWindowEffects(keepScreenOn = animated && ready && !paused, maxBrightness = maxBrightness)

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
                .background(SignerTheme.colors.qrPlate)
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

    /** How many times [render] ran (tests: a code shown again must not be encoded again). */
    @VisibleForTesting
    val renderCalls = AtomicInteger()

    /** [initial] as images when it was prepared for exactly these frames (`QRCodeView.swift:47`), else null. */
    fun prepared(frames: List<String>, initial: List<QrMatrix>?): RenderedQr? {
        if (initial == null || frames.isEmpty() || initial.size != frames.size) return null
        return RenderedQr(frames, initial.map(QrRendering::imageBitmap))
    }

    /**
     * Encodes and renders every frame (CPU work: call off the main thread); frames that do not fit are left out.
     * Cancellable between frames, so a code that goes away stops costing CPU.
     */
    suspend fun render(frames: List<String>): List<ImageBitmap> {
        renderCalls.incrementAndGet()
        val context = currentCoroutineContext()
        return frames.mapNotNull { frame ->
            context.ensureActive()
            try {
                QrRendering.imageBitmap(encoder.encode(frame))
            } catch (e: QrEncodingException) {
                null
            }
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

/**
 * `QRCodeView.swift:74-93`: bordered circle play/pause (tonal accent), progress in the accent over `fill` (hidden from
 * TalkBack), "i/n" counter in caption digits, secondary label.
 */
@Composable
private fun QrPlaybackControls(index: Int, count: Int, paused: Boolean, onTogglePause: () -> Unit) {
    val colors = SignerTheme.colors
    val counterStyle = SignerTheme.typography.caption.monospacedDigit().copy(
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
                    imageVector = if (isPaused) SignerIcons.Play else SignerIcons.Pause,
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
            color = colors.accent,
            trackColor = colors.fill,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            text = stringResource(L10n.string.x_x_2, index + 1, count),
            style = counterStyle,
            color = colors.secondaryLabel,
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
 * While composed and resumed, on the window that hosts this composition (activity, dialog or bottom sheet): keeps the
 * screen on when [keepScreenOn], runs it at full brightness when [maxBrightness]. Separate effects, so pausing an
 * animated code never touches the brightness.
 */
@Composable
private fun QrDisplayWindowEffects(keepScreenOn: Boolean, maxBrightness: Boolean) {
    val view = LocalView.current
    LifecycleResumeEffect(view, keepScreenOn) {
        val window = if (keepScreenOn) view.hostWindow() else null
        window?.let { QrWindowEffects.acquire(it, QrWindowEffects.Effect.KeepScreenOn) }
        onPauseOrDispose { window?.let { QrWindowEffects.release(it, QrWindowEffects.Effect.KeepScreenOn) } }
    }
    LifecycleResumeEffect(view, maxBrightness) {
        val window = if (maxBrightness) view.hostWindow() else null
        window?.let { QrWindowEffects.acquire(it, QrWindowEffects.Effect.FullBrightness) }
        onPauseOrDispose { window?.let { QrWindowEffects.release(it, QrWindowEffects.Effect.FullBrightness) } }
    }
}

/** One display frame: the shortest part interval [QrCodeView] runs (`delay(0)` would never suspend). */
private const val MIN_INTERVAL_MILLIS = 16L

private val PlateShape = RoundedCornerShape(24.dp)
private val PlatePadding = 18.dp
private val PlateShadow = Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.08f), offset = DpOffset(0.dp, 6.dp))
