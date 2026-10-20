package com.bittool.signer.feature.wallets.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.bittool.signer.core.designsystem.window.LocalWindowGateOpen

/**
 * A top-bar icon action (iOS toolbar `Label(title, systemImage:)` buttons such as "Scan", "Add Wallet", "Copy QR Data"):
 * a Material icon button labelled [label] for TalkBack, with the Material plain tooltip on long press. The tooltip is a
 * popup window, so it only opens while the window gate is open (never above the App Lock window).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        enableUserInput = LocalWindowGateOpen.current,
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}
