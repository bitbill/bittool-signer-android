package com.bittool.signer.core.ui.password

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.ExcludeFromAutofill
import com.bittool.signer.core.designsystem.security.NoPersonalizedLearning
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.l10n.R

/**
 * Which secret a [PasswordField] asks for (iOS `PasswordField.Kind`, `Design/PasswordViews.swift:37-61`). [english] is
 * the iOS raw value: the field's test tag in every language (`"Wallet password"`, `"BIP39 passphrase"`, …), so UI tests
 * address it whatever the interface language; [title] is the translated placeholder.
 */
enum class PasswordKind(val english: String, @StringRes val title: Int) {
    WalletPassword("Wallet password", R.string.wallet_password_2),
    ConfirmPassword("Confirm password", R.string.confirm_password),
    CurrentWalletPassword("Current wallet password", R.string.current_wallet_password),
    NewPassword("New password", R.string.new_password),
    ConfirmNewPassword("Confirm new password", R.string.confirm_new_password),
    Passphrase("BIP39 passphrase", R.string.bip39_passphrase),
    ConfirmPassphrase("Confirm passphrase", R.string.confirm_passphrase),
    ;

    /** Test tag of the eye button of a revealable field (iOS `identifier + ".reveal"`). */
    val revealTag: String get() = "$english.reveal"
}

/**
 * Secure entry for wallet passwords and BIP39 passphrases (iOS `PasswordField`, `Design/PasswordViews.swift:9-98`;
 * design-system §5.14, §9.5; setup-backup §9.1).
 *
 * - The text lives in [state], which the caller creates with `remember { TextFieldState() }` (never the saveable
 *   `rememberTextFieldState()`), and clears right after submitting and on `ON_STOP` (ARCHITECTURE §4.4).
 * - Keyboard: password keyboard, no capitalisation, no autocorrect, no personalised learning (incognito IME). The
 *   window hosting the field is excluded from Autofill (`ExcludeFromAutofill`) while it is shown, so no password
 *   manager offers to save or generate anything; `BasicSecureTextField` itself always reports
 *   `ContentType.Password`, which is why the exclusion sits on the host view. iOS keeps `.password` AutoFill for
 *   existing passwords; DECISIONS §6 excludes Autofill app-wide on Android. [isNew] documents a secret being set for
 *   the first time (the iOS generated-password guard, which the app-wide exclusion already covers).
 * - Accessibility: the field is always named by its translated placeholder (iOS uses the title as the label), also
 *   once text is entered and the placeholder is no longer drawn.
 * - [revealable] (the passphrase only: a mistyped passphrase silently opens a different wallet) adds an eye button
 *   ("Show secret" / "Hide secret", tag `<english>.reveal`). Revealed text is hidden again as soon as the app pauses,
 *   and while shown both the view and the field's semantics node are marked accessibility-sensitive (gaps G5), so
 *   only accessibility tools (TalkBack, Switch Access) can read it.
 * - [imeAction] and [onSubmit] wire the keyboard action (Done submits, Next moves on after [onSubmit]).
 * - The box: [containerColor] (the card colour by default; `raised` on a card) with radius 14 and padding 14, the
 *   translated placeholder in body, entered text laid out left to right.
 */
@Suppress("UNUSED_PARAMETER") // isNew: see the KDoc, kept for the iOS call sites' intent.
@Composable
fun PasswordField(
    kind: PasswordKind,
    state: TextFieldState,
    modifier: Modifier = Modifier,
    isNew: Boolean = false,
    revealable: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    enabled: Boolean = true,
    containerColor: Color = SignerTheme.colors.card,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    var revealed by remember { mutableStateOf(false) }
    // iOS: re-hidden whenever the scene leaves `.active`.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { revealed = false }
    AccessibilitySensitive(active = revealable && revealed)
    ExcludeFromAutofill()
    val placeholder = stringResource(kind.title)
    NoPersonalizedLearning {
        BasicSecureTextField(
            state = state,
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = placeholder
                    isSensitiveData = revealable && revealed
                }
                .testTag(kind.english),
            enabled = enabled,
            textStyle = typography.body.ltr().copy(color = if (enabled) colors.label else colors.secondaryLabel),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
            onKeyboardAction = KeyboardActionHandler { performDefaultAction ->
                onSubmit()
                performDefaultAction()
            },
            textObfuscationMode = if (revealable && revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
            cursorBrush = SolidColor(colors.accent),
            decorator = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(containerColor, SignerShapes.control)
                        .padding(start = 14.dp, end = if (revealable) 2.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty()) {
                            // The field carries the name itself; the drawn placeholder is not read twice.
                            Text(
                                placeholder,
                                style = typography.body,
                                color = colors.tertiaryLabel,
                                maxLines = 1,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                        innerTextField()
                    }
                    if (revealable) {
                        IconButton(
                            onClick = { revealed = !revealed },
                            modifier = Modifier.testTag(kind.revealTag),
                        ) {
                            Icon(
                                imageVector = if (revealed) SignerIcons.VisibilityOff else SignerIcons.Visibility,
                                contentDescription = stringResource(if (revealed) R.string.hide_secret else R.string.show_secret),
                                tint = colors.secondaryLabel,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}
