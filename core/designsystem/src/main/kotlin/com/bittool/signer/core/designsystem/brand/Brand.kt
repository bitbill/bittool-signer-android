package com.bittool.signer.core.designsystem.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The superellipse `|u|^n + |v|^n ≤ 1` filling its bounds: the iOS "continuous corner" of the brand tile (`n = 5`,
 * `scripts/make-brand-assets.py:27`). Used for the brand tile only; cards keep rounded rectangles (design-system §9.4).
 */
class SuperellipseShape(private val n: Float = 5f, private val samples: Int = 256) : Shape {
    init {
        require(n > 0f) { "exponent must be positive" }
        require(samples >= 8) { "too few samples" }
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(path(size))

    /** The outline in [size]: [samples] points at equal angles, closed. Pure geometry, unit-tested. */
    fun path(size: Size): Path {
        val a = size.width / 2.0
        val b = size.height / 2.0
        val e = 2.0 / n
        val path = Path()
        for (i in 0 until samples) {
            val t = 2 * PI * i / samples
            val c = cos(t)
            val s = sin(t)
            val x = (a + a * sign(c) * abs(c).pow(e)).toFloat()
            val y = (b + b * sign(s) * abs(s).pow(e)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    override fun equals(other: Any?): Boolean = other is SuperellipseShape && other.n == n && other.samples == samples
    override fun hashCode(): Int = 31 * n.hashCode() + samples
    override fun toString(): String = "SuperellipseShape(n=$n)"
}

/** Brand geometry on the 1024 grid (design-system §1.2, `scripts/make-brand-assets.py:18-27`). */
internal object BrandGeometry {
    const val GRID = 1024f
    const val CENTER_X = 512f
    const val CENTER_Y = 511.5f
    const val OUTER = 372f
    const val INNER = 288f
    const val NODE_X = 828.5f
    const val NODE_R = 94.8f
    val tileShape = SuperellipseShape(5f)
}

/**
 * The BitTool mark: a ring broken by a solid node, "a closed loop with the point where a key is applied" (exact
 * geometry, design-system §1.2). Square; never mirrored. Decorative: callers give it a label when it stands alone.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.aspectRatio(1f)) { drawBrandMark(color) }
}

/** Draws the mark centred in this scope's square, scaled from the 1024 grid. */
internal fun DrawScope.drawBrandMark(color: Color) {
    val side = size.minDimension
    val u = side / BrandGeometry.GRID
    val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    val ringRadius = (BrandGeometry.OUTER + BrandGeometry.INNER) / 2f
    drawCircle(
        color = color,
        radius = ringRadius * u,
        center = origin + Offset(BrandGeometry.CENTER_X * u, BrandGeometry.CENTER_Y * u),
        style = Stroke(width = (BrandGeometry.OUTER - BrandGeometry.INNER) * u),
    )
    drawCircle(
        color = color,
        radius = BrandGeometry.NODE_R * u,
        center = origin + Offset(BrandGeometry.NODE_X * u, BrandGeometry.CENTER_Y * u),
    )
}

/**
 * The app-icon tile (iOS `BrandTile`, `Design/Logos.swift:224-233`): the white mark on ink `#0A0A0C` in a superellipse,
 * drawn as vectors (sharp at every size; `R.drawable.brand_tile` is the same artwork for windows and the splash). With
 * [shadow], black 16 %, blur `size × 0.1`, offset y `size × 0.04`. Hidden from accessibility.
 */
@Composable
fun BrandTile(size: Dp = 88.dp, modifier: Modifier = Modifier, shadow: Boolean = true) {
    val tile = BrandGeometry.tileShape
    val ink = SignerTheme.colors.brandInk
    Box(
        modifier
            .size(size)
            .then(
                if (shadow) {
                    Modifier.dropShadow(
                        shape = tile,
                        shadow = Shadow(radius = size * 0.1f, color = Color.Black.copy(alpha = 0.16f), offset = DpOffset(0.dp, size * 0.04f)),
                    )
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {},
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawOutline(tile.createOutline(this.size, layoutDirection, this), color = ink)
            drawBrandMark(Color.White)
        }
    }
}

/**
 * The loading stage and the privacy cover (iOS `LaunchView`, `Features/Main/RootView.swift:73-90`, and
 * `PrivacyShieldView`, `Services/PrivacyShield.swift:68-84`): `LaunchBackground` edge to edge, the 96 dp tile, "BitTool
 * Signer" (22 sp semibold) 18 dp below it and the brand line (16 sp, secondary, centred, 30 dp side margins) 18 dp
 * below that.
 *
 * Android deviation (design-system §9.5): the tile sits **exactly at the window centre**, where the system splash draws
 * its icon, so the first frame continues the splash without a jump (an Android splash cannot show text); iOS centres
 * the whole stack. With [captured] the line "Hidden while your screen is recorded or mirrored" (eye-slash, footnote,
 * secondary) sits 24 dp above the navigation bar. Title and line are read as one element.
 */
@Composable
fun LaunchView(modifier: Modifier = Modifier, captured: Boolean = false) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val navigationBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    Layout(
        modifier = modifier
            .fillMaxSize()
            .background(colors.launchBackground)
            .semantics(mergeDescendants = true) {},
        content = {
            BrandTile(size = LaunchTileSize, shadow = false)
            Text(
                text = stringResource(R.string.bittool_signer),
                style = typography.body.copy(fontSize = 22.sp, lineHeight = 28.sp).semibold(),
                color = colors.label,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.air_gapped_signing_for_everyone),
                style = typography.body.copy(fontSize = 16.sp, lineHeight = 21.sp),
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            if (captured) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(SignerIcons.VisibilityOff, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.hidden_while_your_screen_is_recorded_or_mirrored),
                        style = typography.footnote,
                        color = colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 18.dp.roundToPx()
        val margin = 30.dp.roundToPx()
        val textConstraints = Constraints(maxWidth = (width - 2 * margin).coerceAtLeast(0))
        val tile = measurables[0].measure(Constraints())
        val title = measurables[1].measure(textConstraints)
        val slogan = measurables[2].measure(textConstraints)
        val note = measurables.getOrNull(3)?.measure(textConstraints)
        layout(width, height) {
            val tileTop = (height - tile.height) / 2
            tile.place((width - tile.width) / 2, tileTop)
            val titleTop = tileTop + tile.height + gap
            title.place((width - title.width) / 2, titleTop)
            slogan.place((width - slogan.width) / 2, titleTop + title.height + gap)
            note?.place((width - note.width) / 2, height - navigationBottom - 24.dp.roundToPx() - note.height)
        }
    }
}

/** The launch tile is 96 dp, the iOS storyboard size (design-system §1.4); the splash icon uses the same size. */
val LaunchTileSize: Dp = 96.dp
