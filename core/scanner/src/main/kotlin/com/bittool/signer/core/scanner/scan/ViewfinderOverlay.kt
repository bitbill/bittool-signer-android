package com.bittool.signer.core.scanner.scan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.math.max
import kotlin.math.min

/**
 * The dimmed surround with a clear rounded window, mint corner brackets and the reading-progress ring (iOS
 * `ViewfinderOverlay` + `Brackets`, `QRScannerView.swift:139-203`; scan-sign.md §6.5, design-system.md §5.23).
 *
 * - Window: `side = min(width − 64, height × 0.5, 340)` dp, centred and lifted 20 dp; cut-out radius 28 dp. With a
 *   [clearance] (the scanner's top bar and status panel) the window also stays 12 dp clear of both: it moves up first,
 *   then shrinks (never below 120 dp), so the brackets never run through the panel's text (QA sign-09; iOS hides them
 *   behind its blurred material, which Android has no equivalent of).
 * - Dim: the theme's `scannerScrim` (black at 55 %) while the camera is authorised ([active]), the same black at 80 %
 *   otherwise (asking, denied).
 * - Brackets: 5 dp strokes in the theme's `mint` (`Brand.mint`), round caps, arm 16 % of the side, quadratic corners of radius 26 dp; pulsing
 *   0.985 ↔ 1.015 with a 1.2 s ease-in-out, repeating and reversing. With animations removed they stay at 0.985,
 *   exactly where iOS leaves them under Reduce Motion.
 * - Progress ring while `progress > 0`: the window inset by 6 dp, radius 28 dp, traced clockwise from the top-left
 *   corner to `progress` of its length, mint at 90 %, 3 dp round caps. It eases forward (SwiftUI `.smooth(0.2)`) and
 *   snaps back when a new session starts at 0.
 *
 * Decorative: hidden from accessibility (the status panel carries the text). Draws in the draw phase only, so the
 * pulse never recomposes.
 */
@Composable
internal fun ViewfinderOverlay(
    progress: Float,
    active: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
    clearance: ViewfinderClearance? = null,
) {
    val colors = SignerTheme.colors
    val scrim = colors.scannerScrim
    val mint = colors.mint
    val dim by animateFloatAsState(if (active) scrim.alpha else INACTIVE_DIM, tween(200), label = "viewfinderDim")
    val pulse: State<Float> = if (animationsEnabled) {
        rememberInfiniteTransition(label = "viewfinderPulse").animateFloat(
            initialValue = PULSE_MIN,
            targetValue = PULSE_MAX,
            animationSpec = infiniteRepeatable(tween(1_200, easing = EaseInOut), RepeatMode.Reverse),
            label = "viewfinderPulseScale",
        )
    } else {
        remember { mutableFloatStateOf(PULSE_MIN) }
    }
    val ring = remember { Animatable(progress.coerceIn(0f, 1f)) }
    LaunchedEffect(progress) {
        val target = progress.coerceIn(0f, 1f)
        if (target < ring.value) ring.snapTo(target) else ring.animateTo(target, spring(dampingRatio = 1f, stiffness = 987f))
    }

    // Where the overlay starts in the window: the clearance is measured there, the overlay draws in its own space.
    var origin by remember { mutableFloatStateOf(0f) }
    Spacer(
        modifier
            .clearAndSetSemantics { }
            .onGloballyPositioned { origin = it.positionInRoot().y }
            .drawWithCache {
                val band = clearance?.let { (it.top - origin)..(it.bottom - origin) }
                val geometry = ViewfinderGeometry.of(size, this, band)
                val window = geometry.window
                val dimPath = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(window, CornerRadius(geometry.windowRadius)))
                }
                val brackets = bracketsPath(window, geometry.bracketRadius)
                val bracketStroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val ringMeasure = PathMeasure().apply { setPath(ringPath(geometry.ring, geometry.windowRadius), false) }
                val ringLength = ringMeasure.length
                val ringStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val ringSegment = Path()
                onDrawBehind {
                    drawPath(dimPath, scrim.copy(alpha = dim))
                    scale(pulse.value, pivot = window.center) {
                        drawPath(brackets, mint, style = bracketStroke)
                    }
                    val shown = ring.value
                    if (shown > 0f && ringLength > 0f) {
                        ringSegment.reset()
                        ringMeasure.getSegment(0f, ringLength * shown, ringSegment, true)
                        drawPath(ringSegment, mint.copy(alpha = 0.9f), style = ringStroke)
                    }
                }
            },
    )
}

/**
 * The part of the window the viewfinder must leave free, in window coordinates (px): [top] is the bottom of the top
 * bar, [bottom] the top of the status panel. Written by the scanner page as they are placed; read while drawing.
 */
@Stable
internal class ViewfinderClearance {
    var top by mutableFloatStateOf(0f)
    var bottom by mutableFloatStateOf(Float.POSITIVE_INFINITY)
}

/** Where the window sits in a container of [size] (iOS points → dp). */
internal class ViewfinderGeometry(val window: Rect, val ring: Rect, val windowRadius: Float, val bracketRadius: Float) {
    companion object {
        /**
         * The iOS window, kept 12 dp inside [clear] (the free vertical band, in the container's px) when one is given:
         * moved as little as possible, then shrunk to the band, never below 120 dp (it then sits at the band's top).
         */
        fun of(
            size: Size,
            density: Density,
            clear: ClosedFloatingPointRange<Float>? = null,
        ): ViewfinderGeometry = with(density) {
            var side = min(min(size.width - 64.dp.toPx(), size.height * 0.5f), 340.dp.toPx()).coerceAtLeast(0f)
            var top = (size.height - side) / 2f - 20.dp.toPx()
            if (clear != null) {
                val gap = CLEARANCE_GAP.toPx()
                val bandTop = clear.start + gap
                val bandBottom = clear.endInclusive - gap
                if (top < bandTop || top + side > bandBottom) {
                    val band = bandBottom - bandTop
                    side = min(side, max(band, MIN_SIDE.toPx()))
                    top = if (side <= band) top.coerceIn(bandTop, bandBottom - side) else bandTop
                }
            }
            val left = (size.width - side) / 2f
            val window = Rect(left, top, left + side, top + side)
            val inset = 6.dp.toPx()
            ViewfinderGeometry(
                window = window,
                ring = window.deflate(inset),
                windowRadius = 28.dp.toPx(),
                bracketRadius = 26.dp.toPx(),
            )
        }
    }
}

/** iOS `Brackets.path(in:)`: four open corners, each a line, a quadratic curve and a line. */
internal fun bracketsPath(rect: Rect, radius: Float): Path {
    val length = min(rect.width, rect.height) * 0.16f
    val r = radius
    return Path().apply {
        // top-left
        moveTo(rect.left, rect.top + length)
        lineTo(rect.left, rect.top + r)
        quadraticTo(rect.left, rect.top, rect.left + r, rect.top)
        lineTo(rect.left + length, rect.top)
        // top-right
        moveTo(rect.right - length, rect.top)
        lineTo(rect.right - r, rect.top)
        quadraticTo(rect.right, rect.top, rect.right, rect.top + r)
        lineTo(rect.right, rect.top + length)
        // bottom-right
        moveTo(rect.right, rect.bottom - length)
        lineTo(rect.right, rect.bottom - r)
        quadraticTo(rect.right, rect.bottom, rect.right - r, rect.bottom)
        lineTo(rect.right - length, rect.bottom)
        // bottom-left
        moveTo(rect.left + length, rect.bottom)
        lineTo(rect.left + r, rect.bottom)
        quadraticTo(rect.left, rect.bottom, rect.left, rect.bottom - r)
        lineTo(rect.left, rect.bottom - length)
    }
}

/** A rounded rectangle traced clockwise from the end of its top-left corner (where the iOS trim starts). */
internal fun ringPath(rect: Rect, radius: Float): Path {
    val r = min(radius, min(rect.width, rect.height) / 2f).coerceAtLeast(0f)
    val d = 2f * r
    return Path().apply {
        moveTo(rect.left + r, rect.top)
        lineTo(rect.right - r, rect.top)
        arcTo(Rect(rect.right - d, rect.top, rect.right, rect.top + d), -90f, 90f, false)
        lineTo(rect.right, rect.bottom - r)
        arcTo(Rect(rect.right - d, rect.bottom - d, rect.right, rect.bottom), 0f, 90f, false)
        lineTo(rect.left + r, rect.bottom)
        arcTo(Rect(rect.left, rect.bottom - d, rect.left + d, rect.bottom), 90f, 90f, false)
        lineTo(rect.left, rect.top + r)
        arcTo(Rect(rect.left, rect.top, rect.left + d, rect.top + d), 180f, 90f, false)
        close()
    }
}

/** How far the window stays from the top bar and the status panel. */
private val CLEARANCE_GAP = 12.dp

/** The smallest window the clearance may shrink it to. */
private val MIN_SIDE = 120.dp

/** The dim while the camera is not authorised (iOS `Color.black.opacity(0.8)`). */
private const val INACTIVE_DIM = 0.8f

private const val PULSE_MIN = 0.985f
private const val PULSE_MAX = 1.015f

/** SwiftUI `.easeInOut`. */
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
