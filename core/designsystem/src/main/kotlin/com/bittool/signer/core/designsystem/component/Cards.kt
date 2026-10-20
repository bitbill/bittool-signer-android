package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.modifier.pressScale
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerRadius
import com.bittool.signer.core.designsystem.theme.SignerTheme

/**
 * A flat card on the grouped page (iOS `.cardBackground(padding: 16, radius: 22)`, `Design/DesignSystem.swift:42-45`):
 * [color] (default `card`) in a rounded rectangle, no border, no shadow. Variants: review summary (radius 26, padding
 * 18), scan home (26 / 20), option tiles (radius 20), inner summary boxes (`raised`, radius 16, padding 12).
 *
 * With [onClick] the whole card is one button with a ripple and the iOS `PressableCardStyle` feedback (scale 0.98,
 * opacity 0.9). The content fills the card width; [content] is a column (spacing is the caller's).
 */
@Composable
fun SignerCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(SignerMetrics.cardPadding),
    radius: Dp = SignerRadius.card,
    color: Color = SignerTheme.colors.card,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val clickable = if (onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        Modifier
            .pressScale(interaction)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
    } else {
        Modifier.clip(shape)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .background(color, shape)
            .padding(padding),
        content = content,
    )
}

/** A raised inner box of a card (iOS `Palette.raised` rounded 16, padding 12): counterparty, asset and flow boxes. */
@Composable
fun RaisedBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SignerCard(
        modifier = modifier,
        padding = PaddingValues(12.dp),
        radius = SignerRadius.callout,
        color = SignerTheme.colors.raised,
        content = content,
    )
}
