package com.bittool.signer.feature.wallets.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.security.NoPersonalizedLearning
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines

/**
 * "Rename Wallet" (inventory row 29, `WalletDetailView.swift:117-125`): a text field pre-filled with the current name
 * (word capitalisation), the message "The fingerprint and addresses stay the same.", "Cancel" and "Save" (disabled
 * while the trimmed text is empty). No password is asked; the store validates the 1–40 character rule.
 */
@Composable
internal fun RenameWalletDialog(currentName: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val state = remember { TextFieldState(currentName, TextRange(currentName.length)) }
    val canSave = state.text.toString().trimWhitespacesAndNewlines().isNotEmpty()
    val save = { if (canSave) onSave(state.text.toString()) }
    SignerAlertDialog(
        title = stringResource(R.string.rename_wallet),
        text = stringResource(R.string.the_fingerprint_and_addresses_stay_the_same),
        confirm = DialogAction(stringResource(R.string.save), enabled = canSave, testTag = "rename.save", onClick = save),
        dismiss = DialogAction(stringResource(R.string.cancel), testTag = "rename.cancel", onClick = onCancel),
        onDismissRequest = onCancel,
    ) {
        WalletNameField(state, onDone = save)
    }
}

/** "Unable to Rename" with the store's message and "OK" (inventory row 30, `WalletDetailView.swift:126-128`). */
@Composable
internal fun UnableToRenameDialog(message: String, onDismiss: () -> Unit) {
    SignerAlertDialog(
        title = stringResource(R.string.unable_to_rename),
        text = message,
        confirm = DialogAction(stringResource(R.string.ok), onClick = onDismiss),
        onDismissRequest = onDismiss,
    )
}

/** The wallet-name field of the rename dialog: the app's field box on the raised colour, no keyboard learning. */
@Composable
private fun WalletNameField(state: TextFieldState, onDone: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val placeholder = stringResource(R.string.wallet_name)
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        // The dialog window attaches a frame after composition; focusing earlier is ignored.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    NoPersonalizedLearning {
        BasicTextField(
            state = state,
            // No content description: on an editable node TalkBack would read it instead of the name being edited.
            // The placeholder is the field's label node below, read after the text (as `PasswordField` does).
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag("rename.name"),
            textStyle = typography.body.copy(color = colors.label),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = KeyboardActionHandler { onDone() },
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(colors.accent),
            decorator = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(colors.raised, SignerShapes.control)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                        .semantics { text = AnnotatedString(placeholder) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.text.isEmpty()) {
                        Text(placeholder, style = typography.body, color = colors.tertiaryLabel, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                    }
                    inner()
                }
            },
        )
    }
}
