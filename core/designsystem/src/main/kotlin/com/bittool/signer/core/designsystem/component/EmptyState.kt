package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold

/**
 * A centred empty or failure view (iOS `ContentUnavailableView`, design-system §5.22): a 56 dp secondary glyph, the
 * [title] (title2 bold, a heading), the [description] (subheadline, secondary) and an optional [action] (e.g. a
 * `PrimaryButton` "Try Again" limited to 280 dp). Examples: "No Signatures Yet", "Wallets Unavailable".
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Box(modifier.fillMaxWidth().padding(horizontal = SignerMetrics.pagePadding, vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.readableWidth(SignerMetrics.narrowWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = typography.title2.bold(),
                color = colors.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(description, style = typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                action()
            }
        }
    }
}
