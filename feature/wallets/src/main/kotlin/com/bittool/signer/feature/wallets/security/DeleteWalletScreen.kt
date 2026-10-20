package com.bittool.signer.feature.wallets.security

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.SwitchRow
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.text.asString
import com.bittool.signer.feature.wallets.common.DestructiveButton
import com.bittool.signer.feature.wallets.common.WalletIdentityCard
import com.bittool.signer.feature.wallets.common.rememberOverlayBlockedNotice

/** What the Delete Wallet page can ask for. */
internal data class DeleteWalletActions(
    val onConfirmedChange: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onSubmitPassword: (SecretText) -> Unit = {},
    val onCancelPassword: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * Delete Wallet (inventory rows 33–35, iOS `DeleteWalletSheet`, wallets-pairing.md §8.4): the warning, the explicit
 * confirmation, then — biometrics first when App Lock is on — the "Wallet Password" dialog; the vault verifies the
 * password before anything is removed. Decisive controls refuse touches through other apps' overlays.
 */
@Composable
fun DeleteWalletScreen(
    onDeleted: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    viewModel: DeleteWalletViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = rememberStatusToastState()
    LaunchedEffect(uiState.gone) { if (uiState.gone) onClose() }
    LaunchedEffect(viewModel) {
        viewModel.pageEvents.collect { event -> if (event == SecurityPageEvent.Done) onDeleted() }
    }
    DeleteWalletContent(
        uiState = uiState,
        toast = toast,
        actions = DeleteWalletActions(
            onConfirmedChange = viewModel::setConfirmed,
            onDelete = viewModel::confirm,
            onSubmitPassword = viewModel::submit,
            onCancelPassword = viewModel::cancelPassword,
            onCancel = {
                viewModel.cancel()
                onCancel()
            },
        ),
    )
}

@Composable
internal fun DeleteWalletContent(uiState: DeleteWalletUiState, toast: StatusToastState, actions: DeleteWalletActions) {
    val blocked = rememberOverlayBlockedNotice(toast)
    SecurityPage(
        title = stringResource(R.string.delete_wallet),
        busy = uiState.busy || uiState.confirming,
        onCancel = actions.onCancel,
        toast = toast,
    ) {
        WalletIdentityCard(uiState.identity)
        Callout(
            text = stringResource(R.string.this_removes_the_encrypted_wallet_and_its_public_data),
            title = stringResource(R.string.delete_this_wallet),
            tone = Tone.Danger,
        )
        GroupedSection(
            footer = stringResource(
                if (uiState.asksForBiometrics) R.string.security_delete_footer_biometric else R.string.deleting_asks_for_this_wallets_password,
            ),
        ) {
            item {
                SwitchRow(
                    title = stringResource(R.string.i_confirm_i_want_to_delete_this_wallet),
                    icon = null,
                    checked = uiState.confirmed,
                    onCheckedChange = actions.onConfirmedChange,
                    enabled = !uiState.busy,
                    modifier = Modifier.testTag("delete.backupConfirmed").obscuredTouchGuard(blocked),
                )
            }
        }
        uiState.error?.let { Callout(text = it.asString(), tone = Tone.Danger, verbatim = true) }
        DestructiveButton(
            text = stringResource(R.string.delete_wallet),
            icon = SignerIcons.Delete,
            onClick = actions.onDelete,
            busy = uiState.busy || uiState.confirming,
            enabled = uiState.confirmed && !uiState.busy && !uiState.confirming,
            modifier = Modifier.testTag("vaultManagement.submit").obscuredTouchGuard(blocked),
        )
    }
    if (uiState.askingPassword) {
        DeletePasswordDialog(onDelete = actions.onSubmitPassword, onCancel = actions.onCancelPassword, onBlocked = blocked)
    }
}

/**
 * "Wallet Password" (inventory row 34, `WalletSecuritySheets.swift:249-258`): a secure dialog window with the wallet
 * password field (`delete.password`), "Cancel" (clears the field) and the destructive "Delete" — always enabled; an
 * empty field is reported by the page. The field lives only as long as the dialog.
 */
@Composable
internal fun DeletePasswordDialog(onDelete: (SecretText) -> Unit, onCancel: () -> Unit, onBlocked: () -> Unit) {
    val password = remember { TextFieldState() }
    DisposableEffect(password) { onDispose { password.clearText() } }
    val delete = {
        val entered = SecretText.of(password.text)
        password.clearText()
        onDelete(entered)
    }
    val cancel = {
        password.clearText()
        onCancel()
    }
    SignerAlertDialog(
        title = stringResource(R.string.wallet_password),
        text = stringResource(R.string.enter_this_wallets_password_to_delete_it_without_the),
        confirm = DialogAction(
            text = stringResource(R.string.delete),
            destructive = true,
            testTag = "delete.confirm",
            onObscuredTouch = onBlocked,
            onClick = delete,
        ),
        dismiss = DialogAction(text = stringResource(R.string.cancel), testTag = "delete.cancel", onClick = cancel),
        secure = true,
        onDismissRequest = cancel,
    ) {
        PasswordField(
            kind = PasswordKind.WalletPassword,
            state = password,
            // The outer tag wins over the field's own "Wallet password" tag: the e2e id of this field is delete.password.
            modifier = Modifier.testTag("delete.password"),
            imeAction = ImeAction.Done,
            onSubmit = delete,
            containerColor = SignerTheme.colors.raised,
        )
    }
}
