package com.bittool.signer.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection

/**
 * A label with its value on the trailing edge (iOS `LabeledContent`), in the `ValueRow` style of `:core:designsystem`
 * (body label, secondary body value, 16 × 12 padding, 56 dp, one TalkBack node, stacked at accessibility sizes).
 *
 * Local workaround: `ValueRow` gives the value half of the free width but leaves the rest unused after a short label,
 * so values end mid-row instead of at the trailing edge (reported to W1-design). Here the label keeps its own width
 * (at most 60 %), the value takes everything else and is drawn against the trailing edge, wrapping when long.
 */
@Composable
internal fun ValueLine(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics(mergeDescendants = true) {}
    if (SignerTheme.isAccessibilityTextSize) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = typography.body, color = colors.label)
            Text(value, style = typography.body.contentDirection(), color = colors.secondaryLabel)
        }
        return
    }
    Layout(
        modifier = rowModifier,
        content = {
            Text(label, style = typography.body, color = colors.label)
            Text(value, style = typography.body.contentDirection(), color = colors.secondaryLabel, textAlign = TextAlign.End)
        },
    ) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val labelPlaceable = measurables[0].measure(Constraints(maxWidth = (width * 0.6f).toInt()))
        val valueMax = (width - labelPlaceable.width - gap).coerceAtLeast(0)
        val valuePlaceable = measurables[1].measure(Constraints(maxWidth = valueMax))
        val height = maxOf(labelPlaceable.height, valuePlaceable.height, constraints.minHeight)
        layout(width, height) {
            labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
            valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
        }
    }
}
