package com.bittool.signer.feature.wallets.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.designsystem.window.rememberClearFocus
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallets
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.pairing.WalletPairingPayload
import com.bittool.signer.core.scanner.QrCodeView
import com.bittool.signer.core.ui.address.CopyableAddress
import com.bittool.signer.core.ui.logo.ChainLogo
import com.bittool.signer.core.ui.password.PasswordField
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.core.ui.pairing.SubmitWalletLink
import com.bittool.signer.core.ui.pairing.WalletGroup
import com.bittool.signer.core.ui.payload.PayloadDisclosure
import com.bittool.signer.core.ui.qr.QrLoadingPlaceholder
import com.bittool.signer.core.ui.text.Callout
import com.bittool.signer.core.ui.text.localizedLongName
import com.bittool.signer.core.ui.text.localizedTitle
import com.bittool.signer.feature.wallets.common.QrBrightnessCorner
import com.bittool.signer.feature.wallets.common.leftToRight
import com.bittool.signer.feature.wallets.common.rememberOverlayBlockedNotice
import com.bittool.signer.feature.wallets.security.SecurityError
import com.bittool.signer.feature.wallets.security.rememberPasswordFields

/**
 * What the KH v2 password card needs (iOS `WalletIdentityAuthorizationView`), or null when the wallet has its identity
 * signature and Wallet Level shows its code.
 */
internal class IdentityRequest(
    val busy: Boolean,
    val error: UiText?,
    val securityEpoch: Long,
    val toast: StatusToastState,
    val onSubmit: (SecretText) -> Unit,
    val onCancel: () -> Unit,
) {
    companion object {
        fun of(uiState: ConnectUiState, toast: StatusToastState, actions: ConnectActions): IdentityRequest? =
            if (!uiState.needsIdentity) {
                null
            } else {
                IdentityRequest(uiState.identityBusy, uiState.identityError, uiState.securityEpoch, toast, actions.onAuthorizeIdentity, actions.onCancelIdentity)
            }
    }
}

/**
 * Wallet Level (iOS `WalletLevelPairingView`, `ConnectView.swift`, wallets-pairing.md §7.4): BitTool Signer's
 * `bittool-signer-wallet` v2 — the account xpubs for Bitcoin, EVM and TRON and the three Solana addresses in one
 * animated code (≥ 2 frames, 250 ms each, error correction L) carrying the wallet's KH v2 identity signature, with the
 * first address of every account to compare. [matrices] are the prefetched QR matrices of [payload]'s frames, so the
 * first frame is complete without a spinner. With [identity] (a wallet saved before 1.1.0) the code's place holds the
 * password card instead.
 */
@Composable
internal fun WalletLevelPairing(
    payload: WalletPairingPayload?,
    matrices: List<QrMatrix>?,
    error: UiText?,
    masterFingerprint: String?,
    onSubmitWallet: () -> Unit,
    modifier: Modifier = Modifier,
    identity: IdentityRequest? = null,
) {
    val slot: QrSlot<WalletPairingPayload> = when {
        payload != null -> QrSlot.Loaded(payload)
        error != null -> QrSlot.Failed(error)
        else -> QrSlot.Loading
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Intro()
        if (identity != null) {
            IdentityAuthorization(identity)
            Callout(stringResource(R.string.chain_specific_qr_codes_coin_level_remain_the_choice))
            return@Column
        }
        AnimatedContent(
            targetState = slot,
            transitionSpec = { qrTransition(targetState) },
            contentAlignment = Alignment.TopCenter,
            // A repeated identical result (the page always asks again after the prefetch) is the same content.
            contentKey = { (it as? QrSlot.Loaded)?.value?.frames ?: it },
            label = "walletQr",
        ) { shown ->
            when (shown) {
                is QrSlot.Loaded -> Loaded(shown.value, matrices, masterFingerprint, onSubmitWallet)
                is QrSlot.Failed -> Callout(text = shown.text, tone = Tone.Danger)
                QrSlot.Loading -> QrLoadingPlaceholder(maxSide = 300.dp)
            }
        }
        Callout(stringResource(R.string.chain_specific_qr_codes_coin_level_remain_the_choice))
    }
}

/** Headline, explanation and the four chain logos with "BTC · EVM · TRON · SOL" (`ConnectView.swift:375-395`). */
@Composable
private fun Intro() {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.one_animated_qr_code_for_all_four_chains), style = typography.headline, color = colors.label)
        Text(stringResource(R.string.includes_an_account_xpub_for_each_bitcoin_address_type), style = typography.footnote, color = colors.secondaryLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chain.entries.forEach { chain ->
                    val name = chain.localizedLongName()
                    Box(Modifier.clearAndSetSemantics { contentDescription = name }) { ChainLogo(chain, size = 18.dp) }
                }
            }
            Text(stringResource(R.string.btc_evm_tron_sol), style = typography.footnote, color = colors.secondaryLabel)
        }
    }
}

@Composable
private fun Loaded(payload: WalletPairingPayload, matrices: List<QrMatrix>?, masterFingerprint: String?, onSubmitWallet: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // An animated multi-part QR, drawn as large as the screen allows.
        QrBrightnessCorner(maxSide = WALLET_QR_SIDE) { bright ->
            QrCodeView(
                frames = payload.frames,
                maxSide = WALLET_QR_SIDE,
                initial = matrices,
                maxBrightness = bright,
                modifier = Modifier.testTag("connect.walletQR"),
            )
        }
        Text(
            text = stringResource(R.string.scan_with_bittool_wallet_or_another_wallet_that),
            style = typography.footnote,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.this_code_is_version_2_and_needs_bittool_wallet_1_1_0),
            style = typography.caption,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("connect.walletLevelVersion"),
        )
        Verification(payload, masterFingerprint)
        SignerCard(Modifier.testTag("connect.walletLevelWallets")) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.works_with), style = typography.headline, color = colors.label)
                WalletGroup(
                    title = stringResource(R.string.one_qr_for_every_chain),
                    wallets = CompatibleWallets.walletLevel,
                    icon = SignerIcons.QrCodeScanner,
                )
                Text(
                    stringResource(R.string.always_compare_the_first_addresses_in_both_apps_before),
                    style = typography.caption,
                    color = colors.secondaryLabel,
                )
                SubmitWalletLink(onSubmitWallet)
            }
        }
    }
}

/**
 * iOS `WalletIdentityAuthorizationView`: signs a wallet's KH v2 identity, for a wallet saved before 1.1.0. Version 2 of
 * the wallet-level code carries a signature that proves this device holds the private key behind the public keys it
 * exports, and producing one needs the seed. Newer wallets sign it when they are created, so this appears only once per
 * older wallet. Leaving it (Back, Coin Level) simply leaves the page without a code. The typed password is taken and
 * cleared on submit, and dropped the moment the app is suspended.
 */
@Composable
private fun IdentityAuthorization(request: IdentityRequest) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val fields = rememberPasswordFields(count = 1, securityEpoch = request.securityEpoch, busy = request.busy)
    val password = fields[0]
    val clearFocus = rememberClearFocus()
    val blocked = rememberOverlayBlockedNotice(request.toast)
    val cancel by rememberUpdatedState(request.onCancel)
    DisposableEffect(Unit) { onDispose { cancel() } }
    val submit = {
        if (password.text.isNotEmpty() && !request.busy) {
            clearFocus()
            val entered = SecretText.of(password.text)
            fields.clear()
            request.onSubmit(entered)
        }
    }
    SignerCard(Modifier.testTag("connect.walletIdentity")) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(SignerIcons.ShieldLock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.verify_your_password_once), style = typography.headline, color = colors.label)
            }
            Text(
                stringResource(R.string.the_new_pairing_code_carries_this_wallets_identity),
                style = typography.footnote,
                color = colors.secondaryLabel,
            )
            PasswordField(
                kind = PasswordKind.WalletPassword,
                state = password,
                modifier = Modifier.testTag("connect.identityPassword"),
                imeAction = ImeAction.Done,
                onSubmit = submit,
                enabled = !request.busy,
                containerColor = colors.page,
            )
            SecurityError(request.error)
            PrimaryButton(
                text = stringResource(R.string.create_pairing_code),
                onClick = submit,
                busy = request.busy,
                enabled = password.text.isNotEmpty(),
                modifier = Modifier.testTag("connect.identitySubmit").obscuredTouchGuard(blocked),
            )
            Text(
                stringResource(R.string.until_then_this_wallet_has_no_wallet_level_code_chain),
                style = typography.caption,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** `ConnectView.swift:440-471`: the nine first addresses in engine order, the format, the fingerprint, the payload. */
@Composable
private fun Verification(payload: WalletPairingPayload, masterFingerprint: String?) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    SignerCard(Modifier.testTag("connect.walletVerify")) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.verify_the_first_address_of_each_account), style = typography.subheadline.semibold(), color = colors.label)
            // Unknown chain codes are skipped (iOS `chains.first(where:)`).
            val lines = payload.firstAddresses.mapNotNull { line -> Chain.fromPairingCode(line.chain)?.let { line to it } }
            lines.forEach { (line, chain) ->
                key(line.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clearAndSetSemantics {}) { ChainLogo(chain, size = 20.dp) }
                            Text(accountName(chain, line.profile), style = typography.footnote.semibold(), color = colors.label)
                        }
                        Text(line.path, style = typography.caption.monospaced().ltr(), color = colors.secondaryLabel)
                        CopyableAddress(line.address, style = typography.callout)
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.opaqueSeparator))
                }
            }
            DetailLine(label = stringResource(R.string.format), value = leftToRight(walletFormatLine(payload)), monospaced = false)
            if (masterFingerprint != null) DetailLine(label = stringResource(R.string.master_fingerprint), value = masterFingerprint)
            PayloadDisclosure(title = stringResource(R.string.payload_json), text = payload.json, tag = "connect.walletJSON")
            Text(
                stringResource(R.string.public_addresses_and_public_keys_only_no_private_key),
                style = typography.caption,
                color = colors.secondaryLabel,
            )
        }
    }
}

/**
 * The account a row names (iOS `accountName(chain:profile:)`): "Bitcoin · Taproot", "Solana · Trust" — the same preset
 * names the Coin Level pickers show — and the chain's long name where it has one account.
 */
@Composable
private fun accountName(chain: Chain, profile: String): String {
    val presets = when (chain) {
        Chain.Bitcoin -> AccountProfiles.bitcoinSingle
        Chain.Solana -> AccountProfiles.solana
        else -> emptyList()
    }
    val preset = presets.firstOrNull { it.id == profile } ?: return chain.localizedLongName()
    return chain.localizedLongName() + " · " + preset.localizedTitle()
}

/**
 * `bittool-signer-wallet v2 · ur:bytes (canonical CBOR) · 1666 bytes`: protocol tokens verbatim, the raw (ungrouped)
 * byte count with the translated unit. (iOS 1.1.0 still prints "v1" here although its code is v2; this line names the
 * version the engine actually encodes.)
 */
@Composable
private fun walletFormatLine(payload: WalletPairingPayload): String =
    "bittool-signer-wallet v2 · ur:bytes (canonical CBOR) · " + stringResource(R.string.ui_payload_bytes, payload.payloadBytes.toString())

private val WALLET_QR_SIDE = 360.dp
