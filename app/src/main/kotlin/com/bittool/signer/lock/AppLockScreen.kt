package com.bittool.signer.lock

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.brand.BrandTile
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.SignerDropdownMenu
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.password.wipe
import com.bittool.signer.core.ui.text.Callout

/**
 * The App Lock screen (iOS `AppLockView`, Features/Lock/AppLockView.swift:6-128; app-shell.md §11.9, §15.7), the content
 * of the lock window. The password lives only in this composition (`remember { TextFieldState() }`: never saved) and is
 * cleared on every submit, new security epoch, new lock and when the screen goes away.
 */
@Composable
fun AppLockScreen(viewModel: AppLockViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val password = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = rememberSignerHaptics()
    LaunchedEffect(viewModel) {
        viewModel.fieldEvents.collect { event ->
            when (event) {
                AppLockEvent.ClearPassword -> password.wipe()
                // A rejected password (ARCHITECTURE §4.5: warning haptic), then iOS `passwordFocused = true`: focus and
                // keyboard (the lock window keeps the keyboard hidden otherwise).
                AppLockEvent.FocusPassword -> {
                    haptics.warning()
                    runCatching {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                }
            }
        }
    }
    // Prompts appear only while the activity is resumed (BiometricPrompt silently fails otherwise).
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumedChanged(true)
        onPauseOrDispose { viewModel.onResumedChanged(false) }
    }
    // iOS `.onDisappear { password = "" }`.
    DisposableEffect(password) { onDispose { password.wipe() } }
    AppLockContent(
        state = state,
        password = password,
        focusRequester = focus,
        onBiometrics = viewModel::authenticateBiometrics,
        onUsePassword = viewModel::usePassword,
        onUseBiometrics = viewModel::useBiometrics,
        onSelectWallet = viewModel::selectWallet,
        onClearWallet = viewModel::clearWallet,
        onContinueWithoutAppLock = viewModel::continueWithoutAppLock,
        onUnlock = {
            // The field is cleared before the check runs: a wrong password has to be typed again (UIT:789-795).
            val secret = SecretText.of(password.text)
            password.wipe()
            viewModel.unlock(secret)
        },
    )
}

/**
 * Stateless lock screen (screenshots, previews). [onClearWallet] is the menu's "Choose a wallet".
 * [onContinueWithoutAppLock] is the Android-only way out when no unlock path is left (QA L2, [AppLockUiState.noUnlockPath]).
 */
@Composable
fun AppLockContent(
    state: AppLockUiState,
    password: TextFieldState,
    focusRequester: FocusRequester,
    onBiometrics: () -> Unit,
    onUsePassword: () -> Unit,
    onUseBiometrics: () -> Unit,
    onSelectWallet: (WalletId) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    onClearWallet: () -> Unit = {},
    onContinueWithoutAppLock: () -> Unit = {},
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val toast = rememberStatusToastState()
    val overlayBlocked = stringResource(R.string.security_overlay_blocked)
    Box(modifier.fillMaxSize().background(colors.page).testTag(AppLockTags.ROOT)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The insets (bars, cutout and the IME) shrink the scrolling viewport itself, so the focused password
                // field is kept above the keyboard; padding inside the scroll would leave it behind the IME.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(bottom = 24.dp)
                .readableWidth(SignerMetrics.lockWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Spacer(Modifier.height(60.dp - 28.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BrandTile(size = 84.dp)
                Text(
                    text = stringResource(R.string.bittool_signer_is_locked),
                    style = typography.title2.bold(),
                    color = colors.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.unlock_to_view_your_wallets_and_activity),
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
            val mode = when {
                state.noUnlockPath -> LockMode.NoUnlockPath
                state.usePassword -> LockMode.Password
                else -> LockMode.Biometrics
            }
            val onBlocked = { toast.show(overlayBlocked, SignerIcons.WarningFilled, HapticKind.Warning) }
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
                label = "lockMode",
            ) { shown ->
                if (shown == LockMode.NoUnlockPath) {
                    NoUnlockPathCard(state = state, onContinue = onContinueWithoutAppLock, onBlocked = onBlocked)
                } else if (shown == LockMode.Password) {
                    PasswordForm(
                        state = state,
                        password = password,
                        focusRequester = focusRequester,
                        onSelectWallet = onSelectWallet,
                        onClearWallet = onClearWallet,
                        onUnlock = onUnlock,
                        onUseBiometrics = onUseBiometrics,
                        onBlocked = onBlocked,
                    )
                } else {
                    BiometricActions(state = state, onBiometrics = onBiometrics, onUsePassword = onUsePassword)
                }
            }
            state.error?.let { error ->
                val errorInView = remember { BringIntoViewRequester() }
                // Announced when it appears or changes: the keyboard focus goes back to the field, TalkBack's does not.
                Callout(
                    text = error,
                    tone = Tone.Warning,
                    verbatim = true,
                    modifier = Modifier
                        .testTag(AppLockTags.ERROR)
                        .bringIntoViewRequester(errorInView)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                KeepInViewAboveKeyboard(errorInView, key = error)
            }
            Text(
                text = stringResource(R.string.unlocking_the_app_never_authorizes_a_signature),
                style = typography.footnote,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
        StatusToastHost(toast)
    }
}

/**
 * The message under the card stays visible after a rejected password: the field is focused again and the keyboard
 * would otherwise cover it (the scroll keeps only the focused field in view). Re-requested while the keyboard moves;
 * isolated here, so only this node recomposes on every inset frame.
 */
@Composable
private fun KeepInViewAboveKeyboard(requester: BringIntoViewRequester, key: Any) {
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(key, keyboardHeight) {
        // After the frame that laid the message (and the shrunk viewport) out.
        withFrameNanos { }
        requester.bringIntoView()
    }
}

/** What the lock screen offers: the iOS biometric actions or password form, or the Android-only explanation (QA L2). */
private enum class LockMode { Biometrics, Password, NoUnlockPath }

/**
 * QA L2 (Android-only): no unlock path can succeed — every wallet's device key went with the device's screen lock, and
 * so did the fingerprints. Says so in plain words and offers the one way on: "Continue Without App Lock" (App Lock
 * proves it again, then turns itself off). Guarded against touches through overlays like "Unlock".
 */
@Composable
private fun NoUnlockPathCard(state: AppLockUiState, onContinue: () -> Unit, onBlocked: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    SignerCard(padding = PaddingValues(18.dp), modifier = Modifier.testTag(AppLockTags.NO_UNLOCK_PATH)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(SignerIcons.DeviceLock, contentDescription = null, tint = colors.warning, modifier = Modifier.size(22.dp))
                Text(
                    text = stringResource(R.string.security_no_unlock_path_title),
                    style = typography.headline,
                    color = colors.label,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.security_no_unlock_path_message),
                style = typography.subheadline,
                color = colors.secondaryLabel,
            )
            PrimaryButton(
                text = stringResource(R.string.security_no_unlock_path_continue),
                icon = SignerIcons.LockOpen,
                busy = state.continuing,
                enabled = !state.continuing,
                onClick = onContinue,
                modifier = Modifier.testTag(AppLockTags.CONTINUE_WITHOUT_APP_LOCK).obscuredTouchGuard(onBlocked),
            )
        }
    }
}

/**
 * "Unlock with Biometrics" and "Use a Wallet Password" (AppLockView.swift:47-64). The button is the G7.4 text, not the
 * catalogue's "Unlock with %@" (iOS f9092a7): that key's argument is the system's untranslated Face ID / Touch ID, and
 * several of its translations only read right around such a Latin name (strings_security.xml).
 */
@Composable
private fun BiometricActions(state: AppLockUiState, onBiometrics: () -> Unit, onUsePassword: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton(
            text = stringResource(R.string.security_unlock_with_biometrics),
            icon = SignerIcons.Fingerprint,
            busy = state.authenticating,
            enabled = !state.authenticating,
            onClick = onBiometrics,
            modifier = Modifier.testTag(AppLockTags.BIOMETRIC),
        )
        SecondaryButton(
            text = stringResource(R.string.use_a_wallet_password),
            icon = SignerIcons.Key,
            onClick = onUsePassword,
            modifier = Modifier.testTag(AppLockTags.USE_PASSWORD),
        )
    }
}

/** "Enter the password of any wallet on this device." with the wallet picker, the field and "Unlock" (:66-99). */
@Composable
private fun PasswordForm(
    state: AppLockUiState,
    password: TextFieldState,
    focusRequester: FocusRequester,
    onSelectWallet: (WalletId) -> Unit,
    onClearWallet: () -> Unit,
    onUnlock: () -> Unit,
    onUseBiometrics: () -> Unit,
    onBlocked: () -> Unit,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val canUnlock = state.selectedWallet != null && password.text.isNotEmpty() && !state.busy
    SignerCard(padding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.enter_the_password_of_any_wallet_on_this_device),
                style = typography.subheadline,
                color = colors.secondaryLabel,
            )
            WalletPicker(state = state, onSelect = onSelectWallet, onClear = onClearWallet)
            PasswordField(
                kind = PasswordKind.WalletPassword,
                state = password,
                imeAction = ImeAction.Go,
                onSubmit = { if (canUnlock) onUnlock() },
                containerColor = colors.raised,
                modifier = Modifier.focusRequester(focusRequester),
            )
            PrimaryButton(
                text = stringResource(R.string.unlock),
                icon = SignerIcons.LockOpen,
                busy = state.busy,
                enabled = canUnlock,
                onClick = onUnlock,
                modifier = Modifier.testTag(AppLockTags.UNLOCK).obscuredTouchGuard(onBlocked),
            )
            if (state.biometricsAvailable) {
                TextButton(
                    onClick = onUseBiometrics,
                    modifier = Modifier.fillMaxWidth().testTag(AppLockTags.USE_BIOMETRICS),
                ) {
                    Text(
                        text = stringResource(R.string.security_use_biometrics_instead),
                        style = typography.subheadline.semibold(),
                        color = colors.accent,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The wallet menu (iOS menu `Picker("Wallet")`): "Wallet" with the chosen wallet's name, or "Choose a wallet", and a
 * menu of "Choose a wallet" (no wallet, iOS `tag(nil as UUID?)`) followed by the wallet names, the current choice
 * checked. Names only: fingerprints stay behind the lock.
 */
@Composable
private fun WalletPicker(state: AppLockUiState, onSelect: (WalletId) -> Unit, onClear: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.wallet)
    val noWallet = stringResource(R.string.choose_a_wallet)
    val value = state.selectedName ?: noWallet
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(SignerShapes.control)
                .background(colors.raised)
                .clickable(role = Role.DropdownList) { expanded = true }
                // One element: "Wallet", the current choice as its value, and the action that opens the menu.
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = value
                    testTag = AppLockTags.WALLET
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (SignerTheme.isAccessibilityTextSize) {
                // Large text: the label above the choice, so a wallet name is not cut down to a few letters.
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(label, style = typography.subheadline, color = colors.secondaryLabel)
                    Text(value, style = typography.body, color = colors.accent)
                }
            } else {
                Text(label, style = typography.body, color = colors.label)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = value,
                    style = typography.body,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(SignerIcons.UnfoldMore, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        // The app's menu (backlog B29): a popup window of its own on the elevated card colour, which publishes its items'
        // tags as resource ids in debuggable builds. It opens at once: the window gate it checks is not provided around
        // the lock window (the lock screen is what the gate waits for), so it reads as open here.
        SignerDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WalletMenuItem(text = noWallet, selected = state.selectedWallet == null, tag = AppLockTags.NO_WALLET) {
                expanded = false
                onClear()
            }
            state.wallets.forEach { wallet ->
                WalletMenuItem(
                    text = wallet.name,
                    selected = wallet.id == state.selectedWallet,
                    tag = AppLockTags.walletOption(wallet.id),
                ) {
                    expanded = false
                    onSelect(wallet.id)
                }
            }
        }
    }
}

/** One choice of the wallet menu: checked and `selected` when it is the current one, as in `ProfileMenu`. */
@Composable
private fun WalletMenuItem(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = SignerTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(SignerIcons.Check, contentDescription = null, tint = SignerTheme.colors.accent) }
        } else {
            null
        },
        modifier = Modifier.testTag(tag).semantics { this.selected = selected },
    )
}

/** Test tags of the lock screen: the iOS accessibility identifiers, plus Android-only ones. */
object AppLockTags {
    const val ROOT = "appLock"
    const val BIOMETRIC = "appLock.biometric"
    const val USE_PASSWORD = "appLock.usePassword"
    const val WALLET = "appLock.wallet"
    const val UNLOCK = "appLock.unlock"
    const val USE_BIOMETRICS = "appLock.useBiometrics"
    const val ERROR = "appLock.error"

    /** Android-only (QA L2): the explanation that no unlock path is left, and its "Continue Without App Lock". */
    const val NO_UNLOCK_PATH = "appLock.noUnlockPath"
    const val CONTINUE_WITHOUT_APP_LOCK = "appLock.continueWithoutAppLock"

    /** "Choose a wallet", the first item of the [WALLET] menu (no wallet). */
    const val NO_WALLET = "$WALLET.none"

    /** A wallet in the [WALLET] menu: `appLock.wallet.<wallet id>`, like the Wallets tab's `wallet.card.<id>`. */
    fun walletOption(id: WalletId): String = "$WALLET.$id"
}
