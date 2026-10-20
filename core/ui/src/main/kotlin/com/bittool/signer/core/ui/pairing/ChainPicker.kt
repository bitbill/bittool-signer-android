package com.bittool.signer.core.ui.pairing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.designsystem.theme.medium
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.ui.logo.ChainLogo
import com.bittool.signer.core.ui.text.localizedLongName
import kotlin.math.roundToInt

/**
 * The chain switcher of Connect › Coin Level and Addresses (iOS `ChainPicker`, `Features/Wallets/ConnectView.swift:24-78`;
 * wallets-pairing §3.6): four equal tiles on a `tertiaryFill` track (radius 18, padding 4, gap 4), each the chain logo
 * at 26 over its name (caption). The selected tile gets an accent-tinted (14 %) fill with a 1.5 dp accent outline
 * (85 %), radius 14, that slides between tiles with `.snappy(0.28)`, a full-colour logo at 106 % and a bold accent name;
 * the others a muted logo (saturation 0.35, opacity 0.6) and a medium secondary name. Names are hidden at
 * accessibility text sizes (logo and outline carry the choice; TalkBack reads the long name). Selection haptic on
 * every change. Tags `chain.bitcoin|evm|tron|solana`.
 */
@Composable
fun ChainPicker(selection: Chain, onSelect: (Chain) -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val reduceMotion = SignerTheme.reduceMotion
    val showNames = !SignerTheme.isAccessibilityTextSize
    val haptics = rememberSignerHaptics()
    val chains = Chain.entries
    val selectedIndex = chains.indexOf(selection)
    val position = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) { position.animateTo(selectedIndex.toFloat(), SignerMotion.chainPicker(reduceMotion)) }
    Layout(
        modifier = modifier
            .clip(SignerShapes.track)
            .background(colors.tertiaryFill)
            .padding(4.dp)
            .selectableGroup(),
        content = {
            Box(
                Modifier
                    .background(colors.accent.copy(alpha = 0.14f), SignerShapes.control)
                    .border(1.5.dp, colors.accent.copy(alpha = 0.85f), SignerShapes.control),
            )
            chains.forEach { chain ->
                val selected = chain == selection
                val longName = chain.localizedLongName()
                val logoScale by animateFloatAsState(if (selected) 1.06f else 1f, SignerMotion.chainPicker(reduceMotion), label = "logo")
                Column(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(SignerShapes.control)
                        .selectable(selected = selected, role = Role.Tab) {
                            if (!selected) {
                                haptics.selection()
                                onSelect(chain)
                            }
                        }
                        .semantics { contentDescription = longName }
                        .testTag("chain." + chain.wire)
                        .padding(vertical = 9.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier
                            .clearAndSetSemantics {}
                            .scale(logoScale)
                            .alpha(if (selected) 1f else 0.6f)
                            .then(if (selected) Modifier else Modifier.desaturated(0.35f)),
                    ) {
                        ChainLogo(chain, size = 26.dp)
                    }
                    if (showNames) {
                        val nameStyle = if (selected) SignerTheme.typography.caption.bold() else SignerTheme.typography.caption.medium()
                        // iOS `.lineLimit(1).minimumScaleFactor(0.8)`: "Bitcoin" shrinks a little before it is cut at
                        // large (non-accessibility) text sizes on narrow phones.
                        BasicText(
                            text = chain.displayName,
                            style = nameStyle.copy(
                                color = if (selected) colors.accentOnTint else colors.secondaryLabel,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = nameStyle.fontSize * 0.8f, maxFontSize = nameStyle.fontSize),
                            // TalkBack reads the long name from the tile instead (iOS accessibilityLabel).
                            modifier = Modifier.semantics { hideFromAccessibility() },
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val count = chains.size
        val gap = 4.dp.roundToPx()
        val width = constraints.maxWidth
        val tile = ((width - gap * (count - 1)) / count).coerceAtLeast(0)
        val tiles = measurables.drop(1).map { it.measure(Constraints(minWidth = tile, maxWidth = tile)) }
        val height = tiles.maxOf { it.height }
        val p = position.value.coerceIn(0f, (count - 1).toFloat())
        val thumb = measurables[0].measure(Constraints.fixed(tile, height))
        layout(width, height) {
            thumb.placeRelative((p * (tile + gap)).roundToInt(), 0)
            tiles.forEachIndexed { i, placeable -> placeable.placeRelative(i * (tile + gap), (height - placeable.height) / 2) }
        }
    }
}

/** Draws the content with its colour saturation reduced to [saturation] (iOS `.saturation`). */
private fun Modifier.desaturated(saturation: Float): Modifier = drawWithContent {
    val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) }) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        canvas.restore()
    }
}
