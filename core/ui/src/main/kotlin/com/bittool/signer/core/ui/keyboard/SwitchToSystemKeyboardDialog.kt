package com.bittool.signer.core.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.l10n.R as L10n

/**
 * Asked before the recovery phrase leaves [Bip39Keyboard] for the system keyboard (port of Ownbit 5.0's
 * `SwitchToSystemKeyboardDialog`): the system keyboard, or a third-party one, can see and learn the words, and it is
 * only needed for Chinese, Japanese and Korean phrases. Staying is the default (the emphasized, trailing action);
 * dismissing the dialog, Back included, also stays. A secure window like every setup dialog over the phrase.
 */
@Composable
fun SwitchToSystemKeyboardDialog(onKeep: () -> Unit, onSwitch: () -> Unit) {
    SignerAlertDialog(
        title = stringResource(L10n.string.keyboard_switch_title),
        text = stringResource(L10n.string.keyboard_switch_message),
        confirm = DialogAction(stringResource(L10n.string.keyboard_switch_keep), testTag = KEEP_TAG, onClick = onKeep),
        dismiss = DialogAction(stringResource(L10n.string.keyboard_switch_confirm), testTag = SWITCH_TAG, onClick = onSwitch),
        secure = true,
        onDismissRequest = onKeep,
    )
}

/** Test tags of [SwitchToSystemKeyboardDialog]'s buttons. */
object SwitchToSystemKeyboardDialogTags {
    const val KEEP: String = KEEP_TAG
    const val SWITCH: String = SWITCH_TAG
}

private const val KEEP_TAG = "keyboard.switch.keep"
private const val SWITCH_TAG = "keyboard.switch.confirm"
