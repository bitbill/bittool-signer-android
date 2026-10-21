package com.bittool.signer.feature.wallets.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.bittool.signer.feature.wallets.common.WalletIdentityCard
import com.bittool.signer.feature.wallets.common.rememberOverlayBlockedNotice

/**
 * Repair Wallet (inventory row 36, iOS `RepairWalletSheet`, wallets-pairing.md §8.5): the wallet password once, to
 * rebuild the public keys and addresses of a wallet whose public data is missing or damaged. The typed password is
 * taken and cleared on submit, and dropped the moment the app is suspended.
 *
 * "Repair Wallet" and the failure above it sit in the page's bottom bar, above the keyboard, as on Change Password
 * (QA round 3 production-final-01): the action stays in view while the password is typed, and so does the footer
 * under the password.
 */
@Composable
fun RepairWalletScreen(
    onRepaired: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    viewModel: RepairWalletViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fields = rememberPasswordFields(count = 1, securityEpoch = uiState.securityEpoch, busy = uiState.busy)
    val toast = rememberStatusToastState()
    LaunchedEffect(uiState.gone) { if (uiState.gone) onClose() }
    LaunchedEffect(viewModel) {
        viewModel.pageEvents.collect { event -> if (event == SecurityPageEvent.Done) onRepaired() }
    }
    RepairWalletContent(
        uiState = uiState,
        fields = fields,
        toast = toast,
        onSubmit = {
            val password = SecretText.of(fields[0].text)
            fields.clear()
            viewModel.submit(password)
        },
        onCancel = {
            viewModel.cancel()
            fields.clear()
            onCancel()
        },
    )
}

@Composable
internal fun RepairWalletContent(
    uiState: SecurityPageUiState,
    fields: PasswordFields,
    toast: StatusToastState,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val clearFocus = rememberClearFocus()
    val password = fields[0]
    val canSubmit = password.text.isNotEmpty() && !uiState.busy
    val submit = {
        if (canSubmit) {
            clearFocus()
            onSubmit()
        }
    }
    val blocked = rememberOverlayBlockedNotice(toast)
    SecurityPage(
        title = stringResource(R.string.repair_wallet),
        busy = uiState.busy,
        onCancel = onCancel,
        toast = toast,
        bottomActions = {
            SecurityError(uiState.error)
            PrimaryButton(
                text = stringResource(R.string.repair_wallet),
                onClick = submit,
                busy = uiState.busy,
                enabled = password.text.isNotEmpty(),
                modifier = Modifier.testTag("vaultManagement.submit").obscuredTouchGuard(blocked),
            )
        },
    ) {
        WalletIdentityCard(uiState.identity)
        // The password and its footer (what the repair rebuilds and keeps) stay in view together while it is typed.
        Column(Modifier.keepTogetherInView(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PasswordField(PasswordKind.WalletPassword, password, imeAction = ImeAction.Done, onSubmit = submit, enabled = !uiState.busy)
            FormFooter(stringResource(R.string.bittool_signer_decrypts_the_wallet_once_to_rebuild_its))
        }
    }
}
