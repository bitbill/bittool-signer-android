package com.bittool.signer.feature.wallets.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.SegmentedControl
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallets
import com.bittool.signer.core.model.ConnectOptions
import com.bittool.signer.core.model.pairing.PairingPayload
import com.bittool.signer.core.scanner.QrCodeView
import com.bittool.signer.core.ui.address.CopyableAddress
import com.bittool.signer.core.ui.pairing.AddressIndexControl
import com.bittool.signer.core.ui.pairing.ChainPicker
import com.bittool.signer.core.ui.pairing.IntendedWallets
import com.bittool.signer.core.ui.pairing.ProfileMenu
import com.bittool.signer.core.ui.payload.PayloadDisclosure
import com.bittool.signer.core.ui.qr.QrLoadingPlaceholder
import com.bittool.signer.core.ui.text.Callout
import com.bittool.signer.core.ui.text.fingerprintNotes
import com.bittool.signer.core.ui.text.localizedSummary
import com.bittool.signer.core.ui.text.localizedTitle
import com.bittool.signer.feature.wallets.common.QrBrightnessCorner
import com.bittool.signer.feature.wallets.common.leftToRight

/**
 * Coin Level (`ConnectView.swift:160-314`, wallets-pairing.md §7.3): the chain, its options, the code (or the error, or
 * the placeholder), "Export as", the verification card, the notes and the documented wallets — which follow the
 * selection even while the code is loading.
 */
@Composable
internal fun CoinLevelPairing(uiState: ConnectUiState, actions: ConnectActions, modifier: Modifier = Modifier) {
    val options = uiState.options
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ChainPicker(selection = options.chain, onSelect = actions.onSelectChain, modifier = Modifier.fillMaxWidth())
        ChainOptions(options, actions)
        QrSection(uiState, actions.onSelectOutputMode)
        uiState.coin?.let { Verification(it, options, uiState.masterFingerprint) }
        Notes(options)
        IntendedWallets(
            wallets = CompatibleWallets.wallets(options.chain, options.profile, options.outputMode),
            onSubmitWallet = actions.onSubmitWallet,
            chain = options.chain,
            multisig = options.profile.startsWith("multisig-"),
        )
    }
}

/** `ConnectView.swift:189-220`: Bitcoin account type and address type, Solana path, TRON index, nothing for EVM. */
@Composable
private fun ChainOptions(options: ConnectOptions, actions: ConnectActions) {
    when (options.chain) {
        Chain.Bitcoin -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedControl(
                options = listOf(stringResource(R.string.single_sig), stringResource(R.string.multisig)),
                selectedIndex = if (options.multisig) 1 else 0,
                onSelect = { actions.onSelectMultisig(it == 1) },
                modifier = Modifier.fillMaxWidth().testTag("connect.btcKind"),
                testTags = listOf("connect.btcKind.single", "connect.btcKind.multi"),
            )
            val presets = if (options.multisig) AccountProfiles.bitcoinMulti else AccountProfiles.bitcoinSingle
            val selected = presets.firstOrNull { it.id == options.profile } ?: presets.first()
            val titles = presets.associate { it.id to it.localizedTitle() }
            ProfileMenu(
                title = stringResource(R.string.address_type),
                options = presets,
                selection = selected,
                label = { titles[it.id] ?: it.title },
                detail = { it.detail },
                onSelect = { actions.onSelectBitcoinProfile(it.id) },
            )
        }
        Chain.Solana -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val presets = AccountProfiles.solana
            SegmentedControl(
                options = presets.map { it.localizedTitle() },
                selectedIndex = presets.indexOfFirst { it.id == options.solanaProfile }.coerceAtLeast(0),
                onSelect = { actions.onSelectSolanaProfile(presets[it].id) },
                modifier = Modifier.fillMaxWidth().testTag("connect.solanaProfile"),
                testTags = presets.map { "connect.solanaProfile." + it.id },
            )
            Text(
                text = presets.firstOrNull { it.id == options.solanaProfile }?.detail.orEmpty(),
                style = SignerTheme.typography.caption.monospaced().ltr(),
                color = SignerTheme.colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Chain.Tron -> AddressIndexControl(
            index = options.tronIndex,
            onIndexChange = actions.onSelectTronIndex,
            pathPrefix = AccountProfiles.TRON_PATH_PREFIX,
        )
        Chain.Evm -> Unit
    }
}

/** `ConnectView.swift:222-249`: the code with its caption, the error, or the placeholder; then "Export as". */
@Composable
private fun QrSection(uiState: ConnectUiState, onSelectOutputMode: (String) -> Unit) {
    val options = uiState.options
    val coin = uiState.coin
    val error = uiState.error
    val slot: QrSlot<PairingPayload> = when {
        coin != null -> QrSlot.Loaded(coin, uiState.animateCoin)
        error != null -> QrSlot.Failed(error)
        else -> QrSlot.Loading
    }
    val caption = if (options.chain == Chain.Bitcoin && options.multisig) {
        R.string.scan_with_your_multisig_coordinator
    } else {
        R.string.scan_with_your_watch_only_wallet
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedContent(
            targetState = slot,
            transitionSpec = { qrTransition(targetState) },
            contentAlignment = Alignment.TopCenter,
            label = "coinQr",
        ) { shown ->
            when (shown) {
                is QrSlot.Loaded -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QrBrightnessCorner(maxSide = COIN_QR_SIDE) { bright ->
                        QrCodeView(frames = shown.value.frames, maxSide = COIN_QR_SIDE, maxBrightness = bright, modifier = Modifier.testTag("connect.coinQR"))
                    }
                    Text(
                        text = stringResource(caption),
                        style = SignerTheme.typography.footnote,
                        color = SignerTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
                is QrSlot.Failed -> Callout(text = shown.text, tone = Tone.Danger)
                QrSlot.Loading -> QrLoadingPlaceholder(maxSide = COIN_QR_SIDE)
            }
        }
        val modes = options.exportModes
        if (modes.isNotEmpty()) {
            SegmentedControl(
                options = modes.map { it.localizedTitle() },
                selectedIndex = modes.indexOfFirst { it.wire == options.outputMode }.coerceAtLeast(0),
                onSelect = { onSelectOutputMode(modes[it].wire) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("pairing.outputMode"),
                testTags = modes.map { "pairing.outputMode." + it.wire },
            )
        }
    }
}

/**
 * The verification card (`ConnectView.swift:268-298`): the first address to compare, the path lines, the format, the
 * master fingerprint and the payload — "Format, then fingerprint, then payload — the same order as wallet level."
 */
@Composable
private fun Verification(payload: PairingPayload, options: ConnectOptions, masterFingerprint: String?) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val multisig = options.chain == Chain.Bitcoin && options.multisig
    SignerCard(Modifier.testTag("connect.verify")) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (payload.address.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(if (options.chain == Chain.Bitcoin) R.string.verify_the_first_receive_address else R.string.verify_the_address),
                        style = typography.subheadline.semibold(),
                        color = colors.label,
                    )
                    CopyableAddress(payload.address, style = typography.callout)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.opaqueSeparator))
            }
            if (options.outputMode == "accountXpub" || (options.chain == Chain.Evm && options.outputMode == "wallet")) {
                DetailLine(
                    label = stringResource(if (multisig) R.string.cosigner_account_path else R.string.account_path),
                    value = payload.path,
                )
                if (payload.address.isNotEmpty()) {
                    DetailLine(label = stringResource(R.string.first_address_path), value = payload.path + "/0/0")
                }
            } else {
                DetailLine(label = stringResource(R.string.derivation_path), value = payload.path)
            }
            val view = payload.payloadView
            if (view != null) DetailLine(label = stringResource(R.string.format), value = leftToRight(view.localizedSummary()), monospaced = false)
            if (masterFingerprint != null) DetailLine(label = stringResource(R.string.master_fingerprint), value = masterFingerprint)
            if (view != null) {
                PayloadDisclosure(
                    title = view.localizedTitle(),
                    text = view.text,
                    notes = fingerprintNotes(view, masterFingerprint),
                    tag = "connect.payload",
                )
            }
        }
    }
}

/** `ConnectView.swift:308-314`. */
@Composable
private fun Notes(options: ConnectOptions) {
    when {
        options.outputMode == "address" -> Callout(stringResource(R.string.address_only_no_public_key_or_derivation_path_is_2))
        options.chain == Chain.Bitcoin && options.multisig -> Callout(stringResource(R.string.this_exports_one_cosigner_key_your_multisig_coordinator))
    }
}

private val COIN_QR_SIDE = 300.dp
