@file:OptIn(ExperimentalMaterial3Api::class)

package com.bittool.signer.feature.setup.flow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.bittool.signer.core.crypto.bip39.Bip39Canonical
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.component.SegmentedControl
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.SwitchRow
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.NoPersonalizedLearning
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.designsystem.window.HideOverlayWindowsEffect
import com.bittool.signer.core.designsystem.window.appExposesTestTags
import com.bittool.signer.core.designsystem.window.rememberWindowGateReleased
import com.bittool.signer.core.designsystem.window.windowTestTags
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.scanning.QrScanPurpose
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.scanner.QrScannerScreen
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.text.asString
import com.bittool.signer.feature.setup.phrase.RecoveryPhraseEditor
import com.bittool.signer.feature.setup.phrase.RecoveryPhraseStatus
import com.bittool.signer.feature.setup.phrase.RecoverySuggestionBar
import com.bittool.signer.feature.setup.ui.ErrorCallout
import com.bittool.signer.feature.setup.ui.SetupNavigation
import com.bittool.signer.feature.setup.ui.SetupScaffold
import com.bittool.signer.feature.setup.ui.SetupTopBar
import com.bittool.signer.core.l10n.R as L10n

/**
 * Step 1 of creating (iOS `CreateWalletStep`, `WalletSetupFlow.swift:126-165`; setup-backup §6.1): the wallet name,
 * the phrase length (24 or 12 words) and the BIP39 passphrase option, then "Create Recovery Phrase".
 */
@Composable
internal fun CreateWalletStep(state: WalletSetupUiState, model: WalletSetupViewModel) {
    val fields = model.fields
    val canCreate by remember(state.usePassphrase) {
        derivedStateOf { fields.trimmedName().isNotEmpty() && fields.passphraseValid(state.usePassphrase) }
    }
    val lengthLabel = stringResource(L10n.string.recovery_phrase_length)
    SetupScaffold(
        topBar = { scroll ->
            SetupTopBar(
                title = stringResource(L10n.string.new_wallet),
                navigation = SetupNavigation.Cancel,
                onNavigate = model::cancel,
                navigationEnabled = !state.busy,
                scrollBehavior = scroll,
            )
        },
        step = 1,
        total = 4,
        title = stringResource(L10n.string.create_a_wallet),
        message = stringResource(L10n.string.bittool_signer_creates_a_new_recovery_phrase_on_this),
        footer = {
            ErrorCallout(state.error)
            PrimaryButton(
                text = stringResource(L10n.string.create_recovery_phrase),
                onClick = model::startCreate,
                busy = state.busy,
                enabled = !state.busy && canCreate,
                modifier = Modifier.testTag("setup.create"),
            )
        },
    ) {
        WalletNameField(fields.name, imeAction = ImeAction.Done)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel(lengthLabel)
            SegmentedControl(
                options = listOf(stringResource(L10n.string.n_24_words), stringResource(L10n.string.n_12_words)),
                selectedIndex = if (state.wordCount == 24) 0 else 1,
                onSelect = { model.setWordCount(if (it == 0) 24 else 12) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = lengthLabel }.testTag("setup.wordCount"),
            )
            Text(
                stringResource(L10n.string.both_are_secure_24_words_is_common_for_long_term_storage),
                style = SignerTheme.typography.footnote,
                color = SignerTheme.colors.secondaryLabel,
            )
        }
        PassphraseOptions(state.usePassphrase, model)
    }
}

/**
 * Step 1 of importing (iOS `ImportWalletStep`, `WalletSetupFlow.swift:167-243`; setup-backup §7.1): the live status and
 * the phrase editor first, then the name and the passphrase option. "Scan Recovery QR" opens the camera; while the
 * editor has the keyboard, word suggestions sit on top of it under "Continue".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportWalletStep(state: WalletSetupUiState, model: WalletSetupViewModel) {
    val fields = model.fields
    val focusManager = LocalFocusManager.current
    var phraseFocused by remember { mutableStateOf(false) }
    val suggestions = phraseFocused && WindowInsets.isImeVisible
    val canContinue by remember { derivedStateOf { !fields.phraseIsBlank() } }
    SetupScaffold(
        topBar = { scroll ->
            SetupTopBar(
                title = stringResource(L10n.string.import_wallet),
                navigation = SetupNavigation.Cancel,
                onNavigate = model::cancel,
                navigationEnabled = !state.busy,
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = model::openScanner, enabled = !state.busy, modifier = Modifier.testTag("setup.scanRecovery")) {
                        Icon(SignerIcons.QrCodeScanner, contentDescription = stringResource(L10n.string.scan_recovery_qr))
                    }
                },
            )
        },
        step = 1,
        total = 2,
        title = stringResource(L10n.string.import_a_wallet),
        message = stringResource(L10n.string.enter_the_12_15_18_21_or_24_words_of_an_existing_bip39),
        footerBottomPadding = if (suggestions) 0.dp else 8.dp,
        footer = {
            ErrorCallout(state.error)
            PrimaryButton(
                text = stringResource(L10n.string.continue_text),
                onClick = model::startImport,
                busy = state.busy,
                enabled = !state.busy && canContinue,
                modifier = Modifier.testTag("setup.import"),
            )
            AnimatedVisibility(
                visible = suggestions,
                enter = expandVertically(SignerMotion.quick()) + fadeIn(SignerMotion.quick()),
                exit = shrinkVertically(SignerMotion.quick()) + fadeOut(SignerMotion.quick()),
            ) {
                RecoverySuggestionBar(
                    candidates = state.phrase.candidates,
                    onInsert = model::insertCandidate,
                    onDone = { focusManager.clearFocus() },
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RecoveryPhraseStatus(state.phrase)
            RecoveryPhraseEditor(fields.phrase, onFocusChanged = { phraseFocused = it })
        }
        WalletNameField(fields.name, imeAction = ImeAction.Default)
        PassphraseOptions(state.usePassphrase, model)
    }
    if (state.scanning) RecoveryScannerDialog(state.scanError, model)
    if (state.languageChoices.isNotEmpty()) {
        BackupLanguageDialog(state.languageChoices, onChoose = model::acceptImport, onDismiss = model::dismissLanguageChoices)
    }
}

/**
 * The BIP39 passphrase option (iOS `PassphraseOptions`, `WalletSetupFlow.swift:245-277`; setup-backup §6.1.1): a switch
 * card; when on, both passphrase fields (revealable, so a typo can be read back) and the warning. Switching it off
 * hides the fields but keeps their text.
 */
@Composable
internal fun PassphraseOptions(usePassphrase: Boolean, model: WalletSetupViewModel) {
    val fields = model.fields
    SignerCard(padding = PaddingValues(0.dp)) {
        SwitchRow(
            title = stringResource(L10n.string.bip39_passphrase),
            subtitle = stringResource(L10n.string.advanced_adds_a_25th_word),
            icon = null,
            checked = usePassphrase,
            onCheckedChange = model::setUsePassphrase,
            modifier = Modifier.testTag("setup.usePassphrase"),
        )
        AnimatedVisibility(
            visible = usePassphrase,
            enter = expandVertically(SignerMotion.disclosure()) + fadeIn(SignerMotion.quick()),
            exit = shrinkVertically(SignerMotion.disclosure()) + fadeOut(SignerMotion.quick()),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecretPair(
                    // A revealed passphrase is plain text: its menu, like the phrase editor's, sends nothing anywhere.
                    first = {
                        PasswordField(
                            PasswordKind.Passphrase, fields.passphrase,
                            isNew = true, revealable = true, imeAction = ImeAction.Next, containerColor = Color.Transparent,
                        )
                    },
                    second = {
                        PasswordField(
                            PasswordKind.ConfirmPassphrase, fields.passphraseConfirmation,
                            isNew = true, revealable = true, imeAction = ImeAction.Done, containerColor = Color.Transparent,
                        )
                    },
                    color = SignerTheme.colors.raised,
                    shape = SignerShapes.control,
                )
                Callout(stringResource(L10n.string.each_passphrase_opens_a_different_wallet_back_it_up), tone = Tone.Warning)
            }
        }
    }
}

/** Two secret fields in one rounded container with an inset hairline between them (iOS `VStack(spacing: 0)` + `Divider`). */
@Composable
internal fun SecretPair(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    color: Color,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(color, shape)) {
        first()
        HorizontalDivider(Modifier.padding(start = 14.dp), thickness = 0.5.dp, color = SignerTheme.colors.opaqueSeparator)
        second()
    }
}

/**
 * "Wallet name" (iOS `TextField("Wallet name")`): words capitalised, a card-coloured box with radius 14, any change
 * longer than 40 grapheme clusters cut to the first 40 (`prefix(40)`), incognito keyboard. `setup.name`.
 */
@Composable
internal fun WalletNameField(state: TextFieldState, imeAction: ImeAction, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val label = stringResource(L10n.string.wallet_name)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The field carries the name for TalkBack; the visible label is not read twice.
        FieldLabel(label, Modifier.clearAndSetSemantics {})
        NoPersonalizedLearning {
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().testTag("setup.name"),
                inputTransformation = WalletNameLimit,
                textStyle = typography.body.copy(color = colors.label),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = imeAction),
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = SolidColor(colors.accent),
                decorator = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .background(colors.card, SignerShapes.control)
                            .padding(14.dp)
                            .semantics { text = AnnotatedString(label) },
                    ) {
                        if (state.text.isEmpty()) {
                            Text(label, style = typography.body, color = colors.tertiaryLabel, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/** iOS `if value.count > 40 { name = String(value.prefix(40)) }`, in grapheme clusters. */
internal object WalletNameLimit : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val text = asCharSequence()
        if (Graphemes.exceeds(text, WalletSetupViewModel.NAME_LIMIT)) replace(0, length, Graphemes.prefix(text, WalletSetupViewModel.NAME_LIMIT))
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SignerTheme.typography.subheadline.semibold(), color = SignerTheme.colors.label, modifier = modifier)
}

/**
 * The recovery QR scanner (iOS `fullScreenCover` with `QRScannerView`, `WalletSetupFlow.swift:217-229`): the shared
 * full-screen scanner in a secure, edge-to-edge dialog of this activity (a separate activity would suspend the
 * session). It waits for the window gate like every window.
 */
@Composable
private fun RecoveryScannerDialog(error: UiText?, model: WalletSetupViewModel) {
    if (!rememberWindowGateReleased()) return
    Dialog(
        onDismissRequest = model::closeScanner,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        HideOverlayWindowsEffect()
        // Its own window: it publishes its test tags itself (debuggable builds only).
        Box(Modifier.fillMaxSize().windowTestTags(appExposesTestTags())) {
            QrScannerScreen(
                title = stringResource(L10n.string.scan_recovery_qr),
                hint = stringResource(L10n.string.scan_a_qr_code_containing_the_words_such_as_bittool),
                purpose = QrScanPurpose.RecoveryPhrase,
                progress = null,
                status = null,
                error = error?.asString(),
                onReset = model::resetScanError,
                onClose = model::closeScanner,
                onScan = model::onRecoveryQrScanned,
            )
        }
    }
}

/**
 * "Which language is your backup in?" (iOS `confirmationDialog`, `WalletSetupFlow.swift:230-241`): one button per valid
 * reading in `interpretations` order ("Español: obra diadema gorila…"), and Cancel. The buttons show recovery words:
 * a secure, accessibility-sensitive window.
 */
@Composable
internal fun BackupLanguageDialog(choices: List<Bip39Canonical>, onChoose: (Bip39Canonical) -> Unit, onDismiss: () -> Unit) {
    SignerAlertDialog(
        title = stringResource(L10n.string.which_language_is_your_backup_in),
        text = stringResource(L10n.string.these_words_are_valid_in_more_than_one_language_and),
        confirm = DialogAction(stringResource(L10n.string.cancel), testTag = "setup.language.cancel", onClick = onDismiss),
        secure = true,
        onDismissRequest = onDismiss,
    ) {
        AccessibilitySensitive(active = true)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (reading in choices) {
                SecondaryButton(
                    text = stringResource(L10n.string.x_x, reading.language.displayName, reading.words.take(3).joinToString(" ")),
                    onClick = { onChoose(reading) },
                    modifier = Modifier.testTag("setup.language.${reading.language.fileName}"),
                )
            }
        }
    }
}
