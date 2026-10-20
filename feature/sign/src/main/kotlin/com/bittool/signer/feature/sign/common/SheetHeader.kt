package com.bittool.signer.feature.sign.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R

/**
 * The inline navigation bar of a sheet (iOS `NavigationStack` + `.navigationBarTitleDisplayMode(.inline)` with a
 * `.cancellationAction` "Cancel"): "Cancel" on the leading side and [title] centred over the sheet. When the title does
 * not fit between two Cancel-wide margins (long translations, large text) it moves under the button and wraps instead
 * of being cut off.
 */
@Composable
internal fun SheetHeader(title: String, onCancel: () -> Unit, modifier: Modifier = Modifier, cancelEnabled: Boolean = true) {
    val typography = SignerTheme.typography
    Layout(
        modifier = modifier.heightIn(min = 52.dp).padding(horizontal = 8.dp),
        content = {
            TextAction(stringResource(R.string.cancel), onCancel, enabled = cancelEnabled)
            Text(
                text = title,
                style = typography.headline,
                color = SignerTheme.colors.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val cancel = measurables[0].measure(Constraints(maxWidth = width))
        val gap = 8.dp.roundToPx()
        val centred = width - 2 * (cancel.width + gap)
        val fitsCentred = centred > 0 && measurables[1].maxIntrinsicWidth(cancel.height) <= centred
        if (fitsCentred) {
            val text = measurables[1].measure(Constraints(maxWidth = centred))
            val height = maxOf(cancel.height, text.height, constraints.minHeight)
            layout(width, height) {
                cancel.placeRelative(0, (height - cancel.height) / 2)
                text.placeRelative((width - text.width) / 2, (height - text.height) / 2)
            }
        } else {
            val text = measurables[1].measure(Constraints(maxWidth = (width - 2 * gap).coerceAtLeast(0)))
            val height = cancel.height + text.height
            layout(width, maxOf(height, constraints.minHeight)) {
                cancel.placeRelative(0, 0)
                text.placeRelative((width - text.width) / 2, cancel.height)
            }
        }
    }
}
