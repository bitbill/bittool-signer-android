package com.bittool.signer.feature.setup.reveal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.window.rememberClearFocus
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.wallet.WalletIdentityHeader
import com.bittool.signer.feature.setup.backup.RecoveryBackupPanel
import com.bittool.signer.feature.setup.ui.BodyText
import com.bittool.signer.feature.setup.ui.ErrorCallout
import com.bittool.signer.feature.setup.ui.LocalRecoverySessions
import com.bittool.signer.feature.setup.ui.OverlayGuardHost
import com.bittool.signer.feature.setup.ui.SetupNavigation
import com.bittool.signer.feature.setup.ui.SetupTopBar
import com.bittool.signer.feature.setup.ui.LocalOverlayBlocked
import kotlinx.coroutines.delay
import com.bittool.signer.core.l10n.R as L10n

/**
 * The saved wallet's "Recovery Phrase" page (iOS `RecoveryPhraseSheet`; setup-backup §10): the wallet password, then the
 * same backup panel as setup. System Back pops the page like Cancel (consumed while unlocking).
 */
@Composable
fun RecoveryPhraseScreen(onClose: () -> Unit, viewModel: RecoveryPhraseViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestClose by rememberUpdatedState(onClose)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.eventFlow.collect { event -> if (event == RecoveryPhraseEvent.Close) latestClose() }
        }
    }
    // Back while unlocking is consumed (iOS disables dismissal while busy); otherwise the NavHost pops the page with the
    // predictive animation, and the ViewModel wipes the password and the phrase when the entry goes.
    BackHandler(enabled = state.busy, onBack = viewModel::back)
    RecoveryPhraseContent(
        state = state,
        password = viewModel.password,
        sessions = viewModel.recoverySessions,
        onUnlock = viewModel::unlock,
        onClose = viewModel::close,
    )
}

/**
 * The stateless page (previews and screenshot tests).
 *
 * Once the password unlocked the wallet nothing is focused (iOS `passwordFocused = false`,
 * `WalletSecuritySheets.swift:114`; QA regression-02). The focused password form must not simply leave the
 * composition: Compose then clears the view focus, and in keyboard mode (the password confirmed with Enter on a
 * hardware keyboard) Android hands it to the first focusable control, which left a focus ring on the Cancel / Done
 * cross. So the form stays until it has given up the focus while the window is held (`rememberClearFocus`), then the
 * phrase replaces it (one frame later).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryPhraseContent(
    state: RecoveryPhraseUiState,
    password: TextFieldState,
    sessions: RecoverySessionFactory,
    onUnlock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val revealed = state.revealed
    var formFocused by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    CompositionLocalProvider(LocalRecoverySessions provides sessions) {
        OverlayGuardHost(modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    SetupTopBar(
                        title = stringResource(L10n.string.recovery_phrase),
                        navigation = SetupNavigation.Cancel,
                        onNavigate = onClose,
                        // The cross of every wallet security page, read as the iOS button (QA wallets-10): "Cancel",
                        // which becomes "Done" once the phrase is shown. It keeps its place, so the title never moves.
                        navigationLabel = stringResource(if (revealed == null) L10n.string.cancel else L10n.string.done),
                        navigationTag = "recovery.close",
                        scrollBehavior = scrollBehavior,
                    )
                },
                containerColor = colors.page,
                contentWindowInsets = WindowInsets(0),
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                ) {
                    Column(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .readableWidth()
                            .padding(SignerMetrics.pagePadding),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        state.identity?.let { WalletIdentityHeader(name = it.name, fingerprint = it.fingerprint, avatarIdentity = it.avatarIdentity, size = 44.dp) }
                        when {
                            // Wiped and leaving: the page slides away without the phrase and without the form.
                            state.closing -> Unit
                            revealed != null && !formFocused -> {
                                // Backgrounding discards the phrase anyway; the key also resets the panel's own state.
                                key(state.securityEpoch) {
                                    RecoveryBackupPanel(
                                        words = revealed.words,
                                        displayedPassphrase = revealed.passphrase,
                                        hasPassphrase = revealed.hasPassphrase,
                                    )
                                }
                                Callout(stringResource(L10n.string.anyone_with_these_words_can_take_your_funds_write_them), tone = Tone.Warning)
                            }
                            else -> PasswordForm(
                                state = state,
                                password = password,
                                onUnlock = onUnlock,
                                onFocusChanged = { formFocused = it },
                                unlocked = revealed != null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The password, its note, an error and Continue. [onFocusChanged] reports whether something in the form has the focus;
 * once [unlocked], the form gives the focus up without handing it to another control (see [RecoveryPhraseContent]).
 */
@Composable
private fun PasswordForm(
    state: RecoveryPhraseUiState,
    password: TextFieldState,
    onUnlock: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    unlocked: Boolean,
) {
    val focus = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Clearing the focus in keyboard mode would focus the first control (View.clearFocus); this clears it while no
    // view of the window may take it, so nothing is focused afterwards, as on iOS. Touch mode is unaffected.
    val clearFocus = rememberClearFocus(force = true)
    var continueFocused by remember { mutableStateOf(false) }
    val submit = {
        // In keyboard mode Continue can hold the focus (Tab from the field, then Enter). The attempt empties the field,
        // which disables Continue, and a disabled control's focus went to the first control, the cross. So the focus
        // goes back to the field first: a wrong password keeps it there (QA wallets-05), success then clears it
        // (regression-02). In touch mode Continue never has the focus.
        if (continueFocused) runCatching { focus.requestFocus() }
        onUnlock()
    }
    LaunchedEffect(Unit) {
        // iOS focuses the field after 350 ms, once the sheet has settled.
        delay(FOCUS_DELAY_MILLIS)
        // Not on a page that is leaving (popped, its exit animation running): the keyboard would flash over the next.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(unlocked) {
        if (!unlocked) return@LaunchedEffect
        clearFocus()
        onFocusChanged(false)
    }
    Column(Modifier.onFocusChanged { onFocusChanged(it.hasFocus) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Stays enabled while unlocking, like iOS (QA wallets-05): disabling the focused field took its focus (to the
        // close button) and closed the keyboard, so a wrong password needed another tap on the field. `unlock` ignores
        // a second submit while busy, and the field was emptied when the attempt started.
        PasswordField(
            kind = PasswordKind.WalletPassword,
            state = password,
            imeAction = ImeAction.Go,
            onSubmit = onUnlock,
            modifier = Modifier.focusRequester(focus),
        )
        BodyText(
            stringResource(L10n.string.the_recovery_phrase_stays_hidden_until_you_choose_to),
            style = SignerTheme.typography.footnote,
            color = SignerTheme.colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ErrorCallout(state.error)
        PrimaryButton(
            text = stringResource(L10n.string.continue_text),
            onClick = submit,
            busy = state.busy,
            enabled = password.text.isNotEmpty() && !state.busy,
            modifier = Modifier
                .padding(top = 6.dp)
                .onFocusChanged { continueFocused = it.hasFocus }
                .obscuredTouchGuard(LocalOverlayBlocked.current)
                .testTag("vaultManagement.submit"),
        )
    }
}

private const val FOCUS_DELAY_MILLIS = 350L
