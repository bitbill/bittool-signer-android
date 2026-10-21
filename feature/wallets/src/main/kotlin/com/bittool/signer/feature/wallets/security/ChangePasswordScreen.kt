package com.bittool.signer.feature.wallets.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.window.rememberClearFocus
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.password.PasswordRequirements
import com.bittool.signer.feature.wallets.common.WalletIdentityCard
import com.bittool.signer.feature.wallets.common.rememberOverlayBlockedNotice

/**
 * Change Password (inventory row 32, iOS `ChangePasswordSheet`, wallets-pairing.md §8.3): the wallet, the current
 * password, the new password twice with the live requirements, and "Change Password". [onChanged] runs after a success
 * (the wallet page then shows "Password Changed"); [onCancel] closes without changing anything.
 *
 * "Change Password" and the failure above it sit in the page's bottom bar, above the keyboard, so the action is in view
 * while the confirmation is typed (QA round 3 production-final-01; setup's Reset Password does the same), and the
 * requirements stay in view with the pair they check (QA setup-02). The iOS footer under the button ("Your recovery
 * phrase, fingerprint and addresses stay the same.") ends the form.
 */
@Composable
fun ChangePasswordScreen(
    onChanged: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    viewModel: ChangePasswordViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fields = rememberPasswordFields(count = 3, securityEpoch = uiState.securityEpoch, busy = uiState.busy)
    val toast = rememberStatusToastState()
    LaunchedEffect(uiState.gone) { if (uiState.gone) onClose() }
    LaunchedEffect(viewModel) {
        viewModel.pageEvents.collect { event ->
            if (event == SecurityPageEvent.Done) {
                fields.clear()
                onChanged()
            }
        }
    }
    ChangePasswordContent(
        uiState = uiState,
        fields = fields,
        toast = toast,
        onSubmit = {
            viewModel.submit(SecretText.of(fields[0].text), SecretText.of(fields[1].text), SecretText.of(fields[2].text))
        },
        onCancel = {
            viewModel.cancel()
            fields.clear()
            onCancel()
        },
    )
}

@Composable
internal fun ChangePasswordContent(
    uiState: SecurityPageUiState,
    fields: PasswordFields,
    toast: StatusToastState,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val clearFocus = rememberClearFocus()
    val current = fields[0]
    val new = fields[1]
    val confirmation = fields[2]
    val valid = ChangePasswordViewModel.isValid(current.text, new.text, confirmation.text)
    val submit = {
        if (valid && !uiState.busy) {
            clearFocus()
            onSubmit()
        }
    }
    val blocked = rememberOverlayBlockedNotice(toast)
    SecurityPage(
        title = stringResource(R.string.change_password),
        busy = uiState.busy,
        onCancel = onCancel,
        toast = toast,
        bottomActions = {
            SecurityError(uiState.error)
            PrimaryButton(
                text = stringResource(R.string.change_password),
                onClick = submit,
                busy = uiState.busy,
                enabled = valid,
                modifier = Modifier.testTag("vaultManagement.submit").obscuredTouchGuard(blocked),
            )
        },
    ) {
        WalletIdentityCard(uiState.identity)
        PasswordField(PasswordKind.CurrentWalletPassword, current, imeAction = ImeAction.Next, enabled = !uiState.busy)
        // The new password, its confirmation and the live requirements stay in view together while either is typed.
        Column(Modifier.keepTogetherInView(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PasswordField(PasswordKind.NewPassword, new, isNew = true, imeAction = ImeAction.Next, enabled = !uiState.busy)
            PasswordField(PasswordKind.ConfirmNewPassword, confirmation, isNew = true, imeAction = ImeAction.Done, enabled = !uiState.busy)
            PasswordRequirements(new.text, confirmation.text, Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
        }
        FormFooter(stringResource(R.string.your_recovery_phrase_fingerprint_and_addresses_stay_the))
    }
}
