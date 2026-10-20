package com.bittool.signer.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.medium
import com.bittool.signer.core.designsystem.theme.semibold
import kotlin.math.roundToInt

/**
 * A segmented picker (iOS `Picker(.segmented)`: "Wallet Level / Coin Level", "Words / QR Code", "Account XPUB /
 * Address", "Formats / Wallets", word counts): a `tertiaryFill` track with a thumb (white with a soft shadow in light,
 * `#636366` in dark) that slides to the selection with the `.snappy(0.2)` spring, labels in subheadline (selected
 * semibold). Selection haptic on every change; exposed as a group of tabs (`Role.Tab`, selected state).
 *
 * Long translations never truncate (design-system §9.5): segments share the width equally while every label fits its
 * share; otherwise each segment is as wide as its label plus an equal share of the rest; when even that does not fit
 * (large text, narrow phones) the segments stay equal and the labels wrap onto more lines, growing the control.
 * [testTags], when given, tag each segment (e2e ids such as `backup.format`).
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTags: List<String>? = null,
) {
    require(options.isNotEmpty()) { "SegmentedControl needs at least one option" }
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val reduceMotion = SignerTheme.reduceMotion
    val haptics = rememberSignerHaptics()
    val selected = selectedIndex.coerceIn(0, options.lastIndex)
    val selectedStyle = typography.subheadline.semibold()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val naturalWidths: List<Int> = remember(options, selectedStyle, density) {
        val padding = with(density) { (SEGMENT_PADDING * 2).roundToPx() }
        options.map { measurer.measure(it, selectedStyle, maxLines = 1, softWrap = false).size.width + padding }
    }
    val position = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) { position.animateTo(selected.toFloat(), SignerMotion.segment(reduceMotion)) }
    Layout(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(TrackShape)
            .background(colors.tertiaryFill)
            .padding(TRACK_INSET)
            .selectableGroup(),
        content = {
            Box(
                Modifier
                    .then(
                        if (!colors.isDark) {
                            Modifier.dropShadow(ThumbShape, Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.12f), offset = DpOffset(0.dp, 2.dp)))
                        } else {
                            Modifier
                        },
                    )
                    .background(colors.segmentThumb, ThumbShape)
                    .then(if (!colors.isDark) Modifier.border(0.5.dp, Color.Black.copy(alpha = 0.04f), ThumbShape) else Modifier),
            )
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .clip(ThumbShape)
                        .selectable(selected = isSelected, enabled = enabled, role = Role.Tab) {
                            if (!isSelected) {
                                haptics.selection()
                                onSelect(index)
                            }
                        }
                        .then(testTags?.getOrNull(index)?.let { Modifier.testTag(it) } ?: Modifier)
                        .padding(horizontal = SEGMENT_PADDING, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = if (isSelected) selectedStyle else typography.subheadline.medium(),
                        color = if (enabled) colors.label else colors.tertiaryLabel,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val count = options.size
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else naturalWidths.sum()
        val widths = segmentWidths(naturalWidths, available)
        val minHeight = (MIN_HEIGHT - TRACK_INSET * 2).roundToPx()
        val segmentPlaceables = measurables.drop(1).mapIndexed { i, m -> m.measure(Constraints(minWidth = widths[i], maxWidth = widths[i])) }
        val height = maxOf(minHeight, segmentPlaceables.maxOf { it.height }, constraints.minHeight)
        val offsets = widths.runningFold(0) { acc, w -> acc + w }
        val p = position.value.coerceIn(0f, (count - 1).toFloat())
        val lower = p.toInt().coerceAtMost(count - 1)
        val upper = (lower + 1).coerceAtMost(count - 1)
        val fraction = p - lower
        val thumbX = offsets[lower] + (offsets[upper] - offsets[lower]) * fraction
        val thumbWidth = widths[lower] + (widths[upper] - widths[lower]) * fraction
        val thumb = measurables[0].measure(Constraints.fixed(thumbWidth.roundToInt().coerceAtLeast(0), height))
        layout(offsets.last(), height) {
            thumb.placeRelative(thumbX.roundToInt(), 0)
            segmentPlaceables.forEachIndexed { i, placeable ->
                placeable.placeRelative(offsets[i], (height - placeable.height) / 2)
            }
        }
    }
}

/**
 * How [available] pixels are shared by segments whose single-line labels need [natural] pixels (pure, unit-tested):
 * equal shares when every label fits one; otherwise label widths plus an equal share of the remainder when they all
 * fit side by side; otherwise equal shares (labels wrap). The result always sums to [available].
 */
fun segmentWidths(natural: List<Int>, available: Int): List<Int> {
    val count = natural.size
    if (count == 0) return emptyList()
    val equal = available / count
    fun distribute(base: List<Int>): List<Int> {
        val rest = available - base.sum()
        val share = rest / count
        val remainder = rest - share * count
        return base.mapIndexed { i, w -> w + share + if (i < remainder) 1 else 0 }
    }
    return when {
        natural.all { it <= equal } || natural.sum() > available -> distribute(List(count) { equal })
        else -> distribute(natural)
    }
}

private val TRACK_INSET = 2.dp
private val SEGMENT_PADDING = 12.dp
private val MIN_HEIGHT = 36.dp
private val TrackShape = RoundedCornerShape(10.dp)
private val ThumbShape = RoundedCornerShape(8.dp)
