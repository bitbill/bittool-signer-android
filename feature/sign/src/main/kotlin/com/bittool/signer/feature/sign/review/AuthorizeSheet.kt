package com.bittool.signer.feature.sign.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.password.wipe
import com.bittool.signer.core.ui.text.Callout
import com.bittool.signer.core.ui.wallet.WalletIdentityHeader
import com.bittool.signer.feature.sign.common.SheetHeader
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.rememberOverlayBlocked
import kotlinx.coroutines.delay

/** iOS `.task { try? await Task.sleep(nanoseconds: 350_000_000); focused = true }`. */
private const val FOCUS_DELAY_MILLIS = 350L

/**
 * "Authorize Signature" (inventory row 53; iOS `AuthorizeSheet`, `Features/Sign/RequestReviewView.swift:821-907`): the
 * wallet that signs, its password ("Wallet password", focused 350 ms after the sheet appears), the failure or the
 * reassurance line, and "Sign" (`authorize.sign`) pinned above the keyboard.
 *
 * - The typed password lives only in this composition (`remember`, never saved): it is moved into a [SecretText] and
 *   cleared from the field the moment Sign is pressed, and cleared when the app stops and when the sheet leaves.
 * - While signing the sheet cannot be dismissed (swipe, scrim, Back) and Cancel is disabled.
 * - A failure refocuses the field with a reject haptic; the person retypes the password.
 * - The window is `FLAG_SECURE`, marked accessibility-sensitive (gaps G5.1), and Sign refuses touches that came
 *   through another app's overlay (gaps G4.2).
 */
@Composable
internal fun AuthorizeSheet(
    state: AuthorizeUiState,
    onSign: (SecretText) -> Unit,
    onCancel: () -> Unit,
    onDismissed: () -> Unit,
) {
    val password = remember { TextFieldState() }
    // wipe(): the text and its undo history, so no Ctrl+Z brings a cleared password back.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { password.wipe() }
    DisposableEffect(password) { onDispose { password.wipe() } }
    val latestState by rememberUpdatedState(state)
    val latestOnSign by rememberUpdatedState(onSign)
    val submit: () -> Unit = remember(password) {
        {
            val current = latestState
            if (password.text.isNotEmpty() && !current.busy && !current.closing) {
                val entered = SecretText.of(password.text)
                password.wipe()
                latestOnSign(entered)
            }
        }
    }
    SignerBottomSheet(
        onDismissRequest = onDismissed,
        secure = true,
        dismissible = !state.busy,
        testTag = SignTags.AUTHORIZE_SHEET,
    ) {
        AccessibilitySensitive(active = true)
        LaunchedEffect(state.closing) {
            if (state.closing) dismiss()
        }
        val toast = rememberStatusToastState()
        val blocked = rememberOverlayBlocked(toast)
        // A window of its own: SignerBottomSheet publishes its test tags as resource ids (e2e, gaps G1.8).
        Box {
            Column {
                SheetHeader(title = stringResource(R.string.authorize_signature), onCancel = onCancel, cancelEnabled = !state.busy)
                AuthorizeBody(state, password, submit, Modifier.weight(1f, fill = false))
                BottomActionBar {
                    PrimaryButton(
                        text = stringResource(R.string.sign),
                        onClick = submit,
                        icon = SignerIcons.Signature,
                        busy = state.busy,
                        enabled = password.text.isNotEmpty() && !state.busy,
                        modifier = Modifier.obscuredTouchGuard(blocked).testTag(SignTags.AUTHORIZE_SIGN),
                    )
                }
            }
            StatusToastHost(toast)
        }
    }
}

/** The scrolling part of the sheet: identity, password, message (also drawn by the screenshot tests). */
@Composable
internal fun ColumnScope.AuthorizeBody(
    state: AuthorizeUiState,
    password: TextFieldState,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val colors = SignerTheme.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = rememberSignerHaptics()
    if (autoFocus) {
        LaunchedEffect(focus) {
            delay(FOCUS_DELAY_MILLIS)
            if (runCatching { focus.requestFocus() }.isSuccess) keyboard?.show()
        }
    }
    // Each failure once: a sheet rebuilt by a rotation shows the error again but does not buzz again.
    var handledAttempt by rememberSaveable { mutableIntStateOf(state.attempt) }
    LaunchedEffect(state.attempt) {
        if (state.attempt > handledAttempt) {
            handledAttempt = state.attempt
            haptics.reject()
            if (runCatching { focus.requestFocus() }.isSuccess) keyboard?.show()
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SignerMetrics.pagePadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        WalletIdentityHeader(
            name = state.wallet.name.orEmpty(),
            fingerprint = state.wallet.fingerprint,
            avatarIdentity = state.wallet.avatarIdentity,
            size = 48.dp,
        )
        PasswordField(
            kind = PasswordKind.WalletPassword,
            state = password,
            modifier = Modifier.focusRequester(focus),
            imeAction = ImeAction.Done,
            onSubmit = onSubmit,
        )
        val error = state.error
        if (error != null) {
            Callout(text = error, tone = Tone.Danger)
        } else {
            Text(
                text = stringResource(R.string.the_password_decrypts_this_wallet_for_this_signature),
                style = SignerTheme.typography.footnote,
                color = colors.secondaryLabel,
            )
        }
    }
}
