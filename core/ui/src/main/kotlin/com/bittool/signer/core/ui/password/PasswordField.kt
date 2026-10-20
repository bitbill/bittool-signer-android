package com.bittool.signer.core.ui.password

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.ExcludeFromAutofill
import com.bittool.signer.core.designsystem.security.LocalSensitiveDataMarking
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
 * - Accessibility does not get the secret (gaps G5, iOS `.privacySensitive()`; backlog B1). The node has password
 *   semantics and is always accessibility-data-sensitive, so on API 34+ only accessibility tools (TalkBack, Switch
 *   Access, UiAutomator) are given it or its events at all; and the text it reports is masked with one bullet per
 *   character, like a platform `EditText` with a password transformation, so not even a tool reads a hidden password
 *   back (UI tests type through the keyboard and check the result elsewhere, never by reading the field). The node
 *   marking honours `LocalSensitiveDataMarking` (the debug UI-test switch of gaps G5.3); the bullets never depend on it.
 * - [revealable] (the passphrase only: a mistyped passphrase silently opens a different wallet) adds an eye button
 *   ("Show secret" / "Hide secret", tag `<english>.reveal`). Revealed text is hidden again as soon as the app pauses;
 *   while shown, the node reports the passphrase (as drawn, for TalkBack users) and the hosting view is marked
 *   accessibility-sensitive too, so only accessibility tools can read it.
 * - No undo (backlog B2): the field keeps no undo history (it is dropped after every edit, also after the caller's
 *   `clearText()`) and swallows the undo / redo shortcuts, so Ctrl+Z never types a cleared secret back. Its text menu
 *   offers Paste and Select All only ([secretTextMenu]: no Copy, Cut, Share, Autofill or other app's text action).
 *   Callers clear a ViewModel-held state with [wipe] so the history goes at once, also while the field is off screen.
 * - [imeAction] and [onSubmit] wire the keyboard action (Done submits, Next moves on after [onSubmit]).
 * - The box: [containerColor] (the card colour by default; `raised` on a card) with radius 14 and padding 14, the
 *   translated placeholder in body, entered text laid out left to right.
 */
@Suppress("UNUSED_PARAMETER") // isNew: see the KDoc, kept for the iOS call sites' intent.
@OptIn(ExperimentalFoundationApi::class)
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
    val shown = revealable && revealed
    // The node-level marking follows the same G5.3 switch as the view-level one; the bullets below do not.
    val markSensitive = LocalSensitiveDataMarking.current
    AccessibilitySensitive(active = shown)
    ExcludeFromAutofill()
    // No undo history, whoever edits the state: typing, the caller's clearText(), a paste.
    LaunchedEffect(state) {
        snapshotFlow { state.undoState.canUndo || state.undoState.canRedo }
            .collect { hasHistory -> if (hasHistory) state.undoState.clearHistory() }
    }
    val placeholder = stringResource(kind.title)
    NoPersonalizedLearning {
        BasicSecureTextField(
            state = state,
            modifier = modifier
                .fillMaxWidth()
                // Outermost, so it overrides the text field's own semantics: the field reports bullets, not the
                // secret (BasicSecureTextField reports its plain text to accessibility).
                .semantics {
                    isSensitiveData = markSensitive
                    if (!shown) editableText = AnnotatedString(maskForAccessibility(state.text.length))
                }
                .testTag(kind.english)
                .secretTextMenu()
                .noUndoShortcuts(),
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
            textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
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
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 14.dp)
                            // The field's name, also once typed text hides the placeholder: a label node merged into the
                            // field and read after its text, like a Material label. Not a content description, which
                            // TalkBack would read (and traverse) instead of a revealed passphrase.
                            .semantics { text = AnnotatedString(placeholder) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.text.isEmpty()) {
                            // Drawn only; the label above is what is read.
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

/**
 * What accessibility is told a hidden field holds: one bullet (U+2022, the drawn mask) per UTF-16 unit of the secret,
 * so the cursor and selection indices it also reports stay inside the text. Pure.
 */
internal fun maskForAccessibility(length: Int): String = "•".repeat(length)
