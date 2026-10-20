package com.bittool.signer.feature.sign.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.l10n.R

/**
 * The `obscuredTouchGuard` feedback (ARCHITECTURE §4.8, gaps.md G4.3): a warning toast "Another app is drawing over
 * BitTool Signer. Close it, then try again." in [toast]'s window, with the warning haptic (it reports a refusal).
 */
@Composable
internal fun rememberOverlayBlocked(toast: StatusToastState): () -> Unit {
    val message by rememberUpdatedState(stringResource(R.string.security_overlay_blocked))
    return remember(toast) { { toast.show(message, SignerIcons.WarningFilled, HapticKind.Warning) } }
}
