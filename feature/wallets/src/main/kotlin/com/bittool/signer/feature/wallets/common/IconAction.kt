package com.bittool.signer.feature.wallets.common

import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import com.bittool.signer.core.designsystem.window.LocalWindowGateOpen

/**
 * A top-bar icon action (iOS toolbar `Label(title, systemImage:)` buttons such as "Scan", "Add Wallet", "Copy QR Data"):
 * a Material icon button labelled [label] for TalkBack, with the Material plain tooltip on long press (and mouse hover,
 * and TalkBack's long-press action). The tooltip is a popup window, so it only opens while the window gate is open
 * (never above the App Lock window).
 *
 * Keyboard focus does not open it, as with the platform's view tooltips (QA wallets-12): Material 3 shows the tooltip of
 * a keyboard-focused anchor for as long as the focus stays, so a focus Android hands out by itself (the first control of
 * a window that gets the focus back in keyboard mode) left "Scan" with its tooltip pinned. The button's accessible name
 * is [label] either way.
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
    val material = rememberTooltipState()
    val inputMode = LocalInputModeManager.current
    val state = remember(material, inputMode) { NoKeyboardFocusTooltipState(material, inputMode) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = state,
        enableUserInput = LocalWindowGateOpen.current,
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}

/**
 * Material's tooltip state without the keyboard-focus trigger: Material opens the tooltip of a focused anchor with
 * [MutatePriority.PreventUserInput], which only a long press also uses, and a long press puts the window in touch
 * mode first. So a [MutatePriority.PreventUserInput] request in keyboard mode is the focus one and is dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class NoKeyboardFocusTooltipState(
    private val material: TooltipState,
    private val inputModeManager: InputModeManager,
) : TooltipState by material {
    override suspend fun show(mutatePriority: MutatePriority) {
        if (mutatePriority == MutatePriority.PreventUserInput && inputModeManager.inputMode == InputMode.Keyboard) return
        material.show(mutatePriority)
    }
}
