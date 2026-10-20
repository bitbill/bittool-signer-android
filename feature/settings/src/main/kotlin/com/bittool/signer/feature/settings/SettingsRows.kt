package com.bittool.signer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection

/*
 * Local workarounds for two `:core:designsystem` rows (reported to W1-design): `ValueRow` and `NavRow(value = …)` give
 * the value half of the free width and leave the rest unused after a short label, so the value (and NavRow's chevron)
 * end mid-row instead of at the trailing edge. These keep the exact tokens of those rows (body label, secondary body
 * value, 16 dp insets, 56 dp height, accent icon, tertiary mirrored chevron) and only fix the measurement.
 */

/** iOS `LabeledContent`: the label, and the value against the trailing edge (one TalkBack node). */
@Composable
internal fun ValueLine(label: String, value: String, modifier: Modifier = Modifier) {
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = ROW_MIN_HEIGHT)
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) {}
    LabelValue(label, value, rowModifier)
}

/**
 * A row that opens a choice (iOS menu `Picker` in a `Form`): accent [icon], [title], the current [value] against the
 * trailing edge and the mirrored chevron. 56 dp, ripple, `Role.Button`.
 */
@Composable
internal fun ChoiceRow(title: String, icon: ImageVector?, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Box(Modifier.weight(1f)) { LabelValue(title, value, Modifier.fillMaxWidth()) }
        Icon(SignerIcons.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(20.dp))
    }
}

/** Label at its own width (at most 60 %), value in the rest against the trailing edge; stacked at accessibility sizes. */
@Composable
private fun LabelValue(label: String, value: String, modifier: Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val labelColor = colors.label
    if (SignerTheme.isAccessibilityTextSize) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = typography.body, color = labelColor)
            Text(value, style = typography.body.contentDirection(), color = colors.secondaryLabel)
        }
        return
    }
    Layout(
        modifier = modifier,
        content = {
            Text(label, style = typography.body, color = labelColor)
            Text(value, style = typography.body.contentDirection(), color = colors.secondaryLabel, textAlign = TextAlign.End)
        },
    ) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val labelPlaceable = measurables[0].measure(Constraints(maxWidth = (width * 0.6f).toInt()))
        val valuePlaceable = measurables[1].measure(Constraints(maxWidth = (width - labelPlaceable.width - gap).coerceAtLeast(0)))
        val height = maxOf(labelPlaceable.height, valuePlaceable.height, constraints.minHeight)
        layout(width, height) {
            labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
            valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
        }
    }
}

private val ROW_MIN_HEIGHT = 56.dp
