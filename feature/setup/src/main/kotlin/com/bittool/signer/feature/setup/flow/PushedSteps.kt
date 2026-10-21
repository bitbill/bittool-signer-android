@file:OptIn(ExperimentalMaterial3Api::class)

package com.bittool.signer.feature.setup.flow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.SymbolHero
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.window.rememberClearFocus
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.password.PasswordRequirements
import com.bittool.signer.core.ui.wallet.WalletIdentityHeader
import com.bittool.signer.feature.setup.backup.BackupVerificationChips
import com.bittool.signer.feature.setup.backup.BackupVerificationGrid
import com.bittool.signer.feature.setup.backup.RecoveryBackupPanel
import com.bittool.signer.feature.setup.backup.RecoveryScreenshotWarning
import com.bittool.signer.feature.setup.backup.SecurityTipsButton
import com.bittool.signer.feature.setup.backup.SecurityTipsSheet
import com.bittool.signer.feature.setup.ui.BodyText
import com.bittool.signer.feature.setup.ui.CancelAction
import com.bittool.signer.feature.setup.ui.ErrorCallout
import com.bittool.signer.feature.setup.ui.LinkButton
import com.bittool.signer.feature.setup.ui.ScrollCentering
import com.bittool.signer.feature.setup.ui.SetupNavigation
import com.bittool.signer.feature.setup.ui.SetupScaffold
import com.bittool.signer.feature.setup.ui.SetupTopBar
import com.bittool.signer.feature.setup.ui.LocalOverlayBlocked
import com.bittool.signer.feature.setup.ui.LargeTitle
import com.bittool.signer.feature.setup.ui.keepTogetherInView
import com.bittool.signer.core.l10n.R as L10n

/**
 * Step 2 of creating (iOS `BackupPhraseStep`, `WalletSetupFlow.swift:279-312`; setup-backup §6.2): the backup panel
 * (rebuilt on every security-epoch change: concealed, Words, its sheets closed), Security Tips and "I’ve Written Them
 * Down in Order", which needs the words (or the cards) to have been shown — the QR code alone does not count.
 *
 * Android addition (QA regression-08): with accessibility text sizes, or on a short window (landscape), Security Tips
 * moves from the pinned bar into the page under the panel, as on the Recovery Phrase page. At font scale 2 the bar
 * with Security Tips and a two-line button took about 45 % of the height and the words scrolled through a slot one
 * row high; the bar now holds what decides the step only (an error, the button and its hint).
 */
@Composable
internal fun BackupPhraseStep(state: WalletSetupUiState, model: WalletSetupViewModel) {
    val fields = model.fields
    var showingTips by remember { mutableStateOf(false) }
    val hasPassphrase by remember(state.usePassphrase) { derivedStateOf { fields.hasPassphrase(state.usePassphrase) } }
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val tipsInPage = SignerTheme.isAccessibilityTextSize || windowHeight < COMPACT_PAGE_HEIGHT
    SetupScaffold(
        topBar = { scroll ->
            SetupTopBar(
                title = stringResource(L10n.string.recovery_phrase),
                navigation = SetupNavigation.Back,
                onNavigate = model::pop,
                scrollBehavior = scroll,
                actions = { CancelAction(enabled = !state.busy, onClick = model::cancel) },
            )
        },
        step = 2,
        total = 4,
        title = stringResource(L10n.string.back_up_your_phrase),
        message = stringResource(L10n.string.write_these_x_words_on_paper_in_order_and_keep_them, state.words.size),
        footer = {
            ErrorCallout(state.error)
            if (!tipsInPage) SecurityTipsButton(onClick = { showingTips = true })
            PrimaryButton(
                text = stringResource(L10n.string.ive_written_them_down_in_order),
                onClick = model::confirmBackup,
                enabled = state.hasRevealedBackup,
                modifier = Modifier.testTag("backup.continue"),
            )
            if (!state.hasRevealedBackup) {
                BodyText(
                    stringResource(L10n.string.reveal_the_words_first),
                    style = SignerTheme.typography.footnote,
                    color = SignerTheme.colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        // Backgrounding conceals the phrase and closes the recovery cards.
        key(state.securityEpoch) {
            RecoveryBackupPanel(
                words = state.words,
                hasPassphrase = hasPassphrase,
                showsTipsLink = false,
                isCovered = showingTips,
                onReveal = model::markRevealed,
            )
        }
        if (tipsInPage) SecurityTipsButton(onClick = { showingTips = true })
    }
    if (showingTips) SecurityTipsSheet(onDismiss = { showingTips = false })
}

/**
 * Below this window height (small phones, landscape) the backup step keeps Security Tips in the page (iOS app-shell
 * "compact": a viewport under 560 points).
 */
private val COMPACT_PAGE_HEIGHT = 560.dp

/**
 * Step 3 of creating (iOS `VerifyBackupStep`, `WalletSetupFlow.swift:314-344`; setup-backup §6.3, §8.8): the fill-in
 * check. The chips stay in the bottom bar; the active blank is kept in the middle of the screen. A screenshot clears the
 * placements and the error and shows the warning. The board holds real words: the screen is accessibility-sensitive.
 */
@Composable
internal fun VerifyBackupStep(state: WalletSetupUiState, model: WalletSetupViewModel) {
    val verification = state.verification
    val scroll = rememberScrollState()
    val centering = remember { ScrollCentering() }
    val active = verification?.activePosition
    var lastActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        // iOS `onChange(of: focusID)`: centred whenever the active blank changes.
        if (active != null && active != lastActive) centering.center(active, scroll)
        lastActive = active
    }
    val latestActive by rememberUpdatedState(active)
    LaunchedEffect(Unit) {
        // Android addition (QA setup-14): on appearance too, but only when the first blank is off screen (large text:
        // the footer takes half the height and the board starts below the fold, so the first chip would fill a blank
        // nobody sees). Waits one frame, so the blanks have been laid out and registered.
        withFrameNanos { }
        latestActive?.let { centering.centerIfHidden(it, scroll) }
    }
    RecoveryScreenshotWarning(onScreenshot = model::onVerificationScreenshot)
    AccessibilitySensitive(active = true)
    SetupScaffold(
        topBar = { bar ->
            SetupTopBar(
                title = stringResource(L10n.string.verify),
                navigation = SetupNavigation.Back,
                onNavigate = model::pop,
                scrollBehavior = bar,
                actions = { CancelAction(enabled = !state.busy, onClick = model::cancel) },
            )
        },
        step = 3,
        total = 4,
        title = stringResource(L10n.string.check_your_backup),
        message = stringResource(L10n.string.using_your_written_copy_tap_the_missing_words_in_order),
        scrollState = scroll,
        centering = centering,
        footer = {
            ErrorCallout(state.error)
            if (verification != null) {
                BackupVerificationChips(verification, onPlace = model::place, modifier = Modifier.padding(bottom = 4.dp))
            }
            PrimaryButton(
                text = stringResource(L10n.string.confirm),
                onClick = model::finishVerification,
                enabled = verification?.isComplete == true,
                modifier = Modifier.testTag("verification.continue"),
            )
            LinkButton(
                text = stringResource(L10n.string.show_the_phrase_again),
                onClick = model::reviewPhraseAgain,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("verification.showPhrase"),
            )
        },
    ) {
        if (verification != null) {
            BackupVerificationGrid(
                verification = verification,
                onSelect = model::select,
                onClear = model::clear,
                onCellPositioned = { position, coordinates -> if (verification.isBlank(position)) centering.register(position, coordinates) },
            )
        }
    }
}

/**
 * The password step (iOS `SetPasswordStep`, `WalletSetupFlow.swift:346-400`; setup-backup §6.4): step 4 of 4 when
 * creating, 2 of 2 when importing. When the phrase and passphrase already belong to a wallet here it becomes "Reset
 * Password" for that wallet (named by its identity header). "Create Wallet" / "Import Wallet" / "Reset Password" refuse
 * touches through overlays.
 *
 * Android differences: the action waits until the duplicate check has answered, with a spinner once the passwords are
 * valid (QA setup-08: the page would otherwise show "Import Wallet" for about a second and then turn into Reset
 * Password, and a quick tap reached the duplicate refusal); the back arrow stays in place while saving, disabled, so
 * the title does not jump (setup-09; iOS hides it in a centred bar); the password pair stays in view with its checklist
 * while typing (setup-02); and a phrase whose wallet here can no longer be unlocked (lost device key, QA backlog B21)
 * explains that instead of offering a reset that cannot work, with the way to that wallet's page, which offers
 * "Remove This Wallet" (B9, L4). A phrase whose wallet here only an unauthenticated sidecar names (QA L3: its public
 * index cannot be authenticated, but its key is fine) leads to that wallet's Repair the same way: importing it would
 * make a second wallet with the same keys, and the unauthenticated identity never authorises a reset.
 */
@Composable
internal fun SetPasswordStep(state: WalletSetupUiState, model: WalletSetupViewModel) {
    val fields = model.fields
    val clearFocus = rememberClearFocus()
    val lostKey = state.duplicateKeyLost && state.duplicateOf != null
    val needsRepair = !lostKey && state.duplicateUnverified && state.duplicateOf != null
    val cannotReset = lostKey || needsRepair
    val resetting = state.duplicateOf != null && !cannotReset
    val create = state.intent == SetupIntent.Create
    val valid by remember { derivedStateOf { fields.passwordValid() } }
    SetupScaffold(
        topBar = { scroll ->
            SetupTopBar(
                title = stringResource(
                    when {
                        cannotReset -> if (create) L10n.string.new_wallet else L10n.string.import_wallet
                        resetting -> L10n.string.reset_password
                        else -> L10n.string.password
                    },
                ),
                navigation = SetupNavigation.Back,
                onNavigate = model::pop,
                navigationEnabled = !state.busy,
                scrollBehavior = scroll,
                actions = { CancelAction(enabled = !state.busy, onClick = model::cancel) },
            )
        },
        step = if (create) 4 else 2,
        total = state.stepTotal,
        title = stringResource(
            when {
                lostKey -> L10n.string.wallets_lost_key_title
                needsRepair -> L10n.string.setup_repair_duplicate_title
                resetting -> L10n.string.reset_password
                else -> L10n.string.set_a_password
            },
        ),
        message = stringResource(
            when {
                lostKey -> L10n.string.setup_lost_key_message
                needsRepair -> L10n.string.setup_repair_duplicate_message
                resetting -> L10n.string.this_recovery_phrase_already_belongs_to_a_wallet_on
                else -> L10n.string.the_password_encrypts_this_wallet_on_this_device_and
            },
        ),
        footer = {
            ErrorCallout(state.error)
            if (cannotReset) {
                PrimaryButton(
                    text = stringResource(L10n.string.setup_lost_key_open_wallet),
                    onClick = model::openLostKeyWallet,
                    modifier = Modifier.testTag(OPEN_LOST_KEY_WALLET_TAG),
                )
            } else {
                PrimaryButton(
                    text = stringResource(
                        when {
                            resetting -> L10n.string.reset_password
                            create -> L10n.string.create_wallet
                            else -> L10n.string.import_wallet
                        },
                    ),
                    onClick = {
                        clearFocus()
                        if (resetting) model.resetPassword() else model.createWallet()
                    },
                    // Passwords typed before the duplicate check answered: the action shows it is still being decided.
                    busy = state.busy || (state.checkingExisting && valid),
                    enabled = valid && !state.busy && !state.checkingExisting,
                    modifier = Modifier.obscuredTouchGuard(LocalOverlayBlocked.current).testTag("setup.finish"),
                )
            }
        },
    ) {
        if (cannotReset) {
            state.identity?.let { identity ->
                SignerCard {
                    WalletIdentityHeader(name = identity.name, fingerprint = identity.fingerprint, avatarIdentity = identity.avatarIdentity)
                }
            }
        } else {
            PasswordForm(state, fields, resetting)
        }
    }
}

/** The password pair, its checklist and the mode callout of [SetPasswordStep]. */
@Composable
private fun PasswordForm(state: WalletSetupUiState, fields: SetupFields, resetting: Boolean) {
    // The pair and its checklist stay visible together while either field is typed in (QA setup-02).
    Column(Modifier.keepTogetherInView(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SecretPair(
            first = {
                PasswordField(
                    PasswordKind.WalletPassword, fields.password,
                    isNew = true, imeAction = ImeAction.Next, containerColor = Color.Transparent,
                )
            },
            second = {
                PasswordField(
                    PasswordKind.ConfirmPassword, fields.confirmation,
                    isNew = true, imeAction = ImeAction.Done, containerColor = Color.Transparent,
                )
            },
            color = SignerTheme.colors.card,
            shape = SignerShapes.callout,
        )
        PasswordRequirements(fields.password.text, fields.confirmation.text, Modifier.padding(horizontal = 4.dp))
    }
    AnimatedContent(
        targetState = resetting,
        transitionSpec = { (fadeIn(SignerMotion.quick()) togetherWith fadeOut(SignerMotion.quick())) using SizeTransform(clip = false) },
        label = "passwordMode",
    ) { reset ->
        if (reset) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.identity?.let { WalletIdentityHeader(name = it.name, fingerprint = it.fingerprint, avatarIdentity = it.avatarIdentity) }
                Callout(stringResource(L10n.string.only_the_password_changes_the_wallet_keeps_its_name))
            }
        } else {
            Callout(stringResource(L10n.string.if_you_forget_this_password_import_this_recovery_phrase))
        }
    }
}

/** "Open Wallet" of the lost-device-key page (QA backlog B21). */
internal const val OPEN_LOST_KEY_WALLET_TAG = "setup.openWallet"

/**
 * The last page (iOS `SetupCompleteStep`, `WalletSetupFlow.swift:402-452`; setup-backup §6.5): the success hero, the
 * created (or reset) wallet and "Pair a Wallet" / "Done". No way back: Back means "Done". The success haptic plays when
 * the commit lands ([SetupEvent.Saved]), not each time this page is composed (a rotation would repeat it).
 */
@Composable
internal fun SetupCompleteStep(state: WalletSetupUiState, model: WalletSetupViewModel) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val created = state.created
    val title = when {
        state.didResetPassword -> L10n.string.password_reset
        state.intent == SetupIntent.Create -> L10n.string.wallet_created
        else -> L10n.string.wallet_imported
    }
    val message = if (state.didResetPassword) {
        L10n.string.this_wallet_is_encrypted_with_your_new_password_use_it
    } else {
        L10n.string.your_keys_are_encrypted_on_this_device_next_pair_a
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.page,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            BottomActionBar(maxWidth = SignerMetrics.narrowWidth) {
                if (created != null && !state.didResetPassword) {
                    PrimaryButton(
                        text = stringResource(L10n.string.pair_a_wallet),
                        icon = SignerIcons.QrCode,
                        onClick = { model.finish(pairWallet = true) },
                        modifier = Modifier.testTag("setup.connect"),
                    )
                }
                if (state.didResetPassword) {
                    PrimaryButton(stringResource(L10n.string.done), onClick = { model.finish(pairWallet = false) }, modifier = Modifier.testTag("setup.done"))
                } else {
                    SecondaryButton(stringResource(L10n.string.done), onClick = { model.finish(pairWallet = false) }, modifier = Modifier.testTag("setup.done"))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .readableWidth(SignerMetrics.narrowWidth)
                    .padding(SignerMetrics.pagePadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SymbolHero(SignerIcons.VerifiedFilled, color = colors.success, size = 64.dp, bounce = true, modifier = Modifier.padding(top = 24.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LargeTitle(stringResource(title), textAlign = TextAlign.Center, modifier = Modifier.testTag("setup.doneTitle"))
                    BodyText(stringResource(message), style = typography.body, color = colors.secondaryLabel, textAlign = TextAlign.Center)
                }
                state.identity?.let { identity ->
                    SignerCard {
                        WalletIdentityHeader(name = identity.name, fingerprint = identity.fingerprint, avatarIdentity = identity.avatarIdentity)
                    }
                }
            }
        }
    }
}
