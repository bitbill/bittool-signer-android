package com.bittool.signer.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold

/** Meaning of a [Callout] (and of risk colours generally, design-system §2.5). */
enum class Tone { Info, Success, Warning, Danger }

/** The tone colour (icons, fills): accent, success, warning or danger. */
fun SignerColors.toneColor(tone: Tone): Color = when (tone) {
    Tone.Info -> accent
    Tone.Success -> success
    Tone.Warning -> warning
    Tone.Danger -> danger
}

/** The glyph of [tone]: `info.circle.fill`, `checkmark.circle.fill`, `exclamationmark.triangle.fill`, `exclamationmark.octagon.fill`. */
fun Tone.icon(): ImageVector = when (this) {
    Tone.Info -> SignerIcons.InfoFilled
    Tone.Success -> SignerIcons.CheckCircleFilled
    Tone.Warning -> SignerIcons.WarningFilled
    Tone.Danger -> SignerIcons.ReportFilled
}

/**
 * A tinted inline message (iOS `Callout`, `Design/DesignSystem.swift:135-184`): the tone glyph aligned with the first
 * line, an optional [title] (subheadline semibold) and [text] (subheadline; primary without a title, secondary under
 * one), wrapping fully, on the tone at 12 % with radius 16 and padding 14. Read as one element; the glyph is
 * decorative. Engine warnings and errors are passed in verbatim with [verbatim] true (iOS `Callout(verbatim:)`): that
 * text is not translated, so it is laid out in its own direction (`contentDirection`) in a right-to-left interface.
 */
@Composable
fun Callout(text: String, modifier: Modifier = Modifier, title: String? = null, tone: Tone = Tone.Info, verbatim: Boolean = false) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val textStyle = typography.subheadline.let { if (verbatim) it.contentDirection() else it }
    val titleStyle = textStyle.semibold()
    val color = colors.toneColor(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), SignerShapes.callout)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FirstLineIcon(tone.icon(), color, if (title != null) titleStyle else textStyle)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (title != null) Text(title, style = titleStyle, color = colors.label)
            Text(text, style = textStyle, color = if (title == null) colors.label else colors.secondaryLabel)
        }
    }
}

/** A glyph centred on the first line of text in [style] (SwiftUI `.firstTextBaseline` alignment of an SF Symbol). */
@Composable
fun FirstLineIcon(icon: ImageVector, tint: Color, style: TextStyle, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val density = LocalDensity.current
    val line = with(density) { style.lineHeight.toDp() }
    val iconSize = with(density) { maxOf(size, (style.fontSize * 1.15f).toDp()) }
    Box(modifier.height(maxOf(line, iconSize)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * A compact status capsule (iOS `StatusPill`, `Design/DesignSystem.swift:186-210`): icon + caption semibold, padding
 * 10 × 5, the colour at 14 % on a capsule, one line. The glyph keeps [color]; the text uses its on-tint variant so it
 * stays readable in light mode (design-system §9.2).
 */
@Composable
fun StatusPill(text: String, icon: ImageVector, color: Color = SignerTheme.colors.accent, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val textColor = colors.onTint(color)
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), SignerShapes.capsule)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = SignerTheme.typography.caption.semibold(),
            color = textColor,
            maxLines = if (SignerTheme.isAccessibilityTextSize) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A large illustrated header (iOS `SymbolHero`, `Design/DesignSystem.swift:212-234`): the glyph at `size × 0.62` on a
 * 12 % disc of `size × 1.7`, inside a 1 dp ring at 18 % of `size × 2.2`. With [bounce] it bounces once on appear
 * (1 → 1.12 → 1), never under reduce motion. Decorative (hidden from accessibility).
 */
@Composable
fun SymbolHero(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    color: Color = SignerTheme.colors.accent,
    size: Dp = 76.dp,
    bounce: Boolean = false,
) {
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
    Box(modifier.size(size * 2.2f).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 2.2f).border(BorderStroke(1.dp, color.copy(alpha = 0.18f)), CircleShape))
        Box(Modifier.size(size * 1.7f).background(color.copy(alpha = 0.12f), CircleShape))
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(size * 0.62f)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}

/**
 * A short instructional step (iOS `StepRow`, `Design/DesignSystem.swift:236-260`): a 36 dp accent-tinted (14 %) disc
 * with the glyph, then [title] (subheadline semibold) over [detail] (subheadline secondary). [number] is accepted and,
 * like iOS, not drawn. One accessibility element.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun StepRow(number: Int?, title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).background(colors.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = typography.subheadline.semibold(), color = colors.label)
            Text(detail, style = typography.subheadline, color = colors.secondaryLabel)
        }
    }
}

/**
 * A label above a selectable value (iOS `DetailLine`, `Design/DesignSystem.swift:262-290`): [label] in caption
 * secondary, [value] in footnote — monospaced and left to right by default, for values the app did not write
 * (addresses, digests, versions); `monospaced = false` for prose. One accessibility element, full width.
 */
@Composable
fun DetailLine(label: String, value: String, modifier: Modifier = Modifier, monospaced: Boolean = true) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, style = typography.caption, color = colors.secondaryLabel)
        SelectionContainer {
            Text(
                text = value,
                style = if (monospaced) typography.footnote.monospaced().ltr() else typography.footnote,
                color = colors.label,
            )
        }
    }
}
