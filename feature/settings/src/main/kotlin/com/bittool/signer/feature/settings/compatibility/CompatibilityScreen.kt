package com.bittool.signer.feature.settings.compatibility

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.SegmentedControl
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallet
import com.bittool.signer.core.ui.logo.ChainGlyph
import com.bittool.signer.core.ui.pairing.WalletGroup
import com.bittool.signer.core.ui.text.localizedLongName
import com.bittool.signer.feature.settings.RowContentPadding
import com.bittool.signer.feature.settings.SectionFrame
import com.bittool.signer.feature.settings.SectionGap
import com.bittool.signer.feature.settings.SettingsPage
import com.bittool.signer.feature.settings.listContentPadding
import com.bittool.signer.feature.settings.rememberResumed

/** Test tags of Compatibility (iOS `compatibility.tab`, plus one per segment for the e2e suite). */
object CompatibilityTags {
    const val TAB = "compatibility.tab"
    const val FORMATS = "compatibility.formats"
    const val WALLETS = "compatibility.wallets"
    const val FORMATS_LIST = "compatibility.formatsList"
}

/** Settings › Compatibility (inventory rows 69–70). [onSubmitWallet] opens "Submit a Wallet" (root level). */
@Composable
fun CompatibilityScreen(
    onBack: () -> Unit,
    onSubmitWallet: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompatibilityViewModel = hiltViewModel(),
) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    CompatibilityContent(
        tab = tab,
        chains = viewModel.chains,
        walletLevel = viewModel.walletLevel,
        directory = viewModel.directory,
        onTabSelected = viewModel::onTabSelected,
        onBack = rememberResumed(onBack),
        onSubmitWallet = rememberResumed(onSubmitWallet),
        modifier = modifier,
    )
}

/**
 * One top bar titled "Compatibility" for both segments (activity-settings.md §6), the "Formats | Wallets" control
 * pinned under it on the page colour, and a ~200 ms cross-fade between the two lists (iOS `.snappy(0.2)`).
 */
@Composable
internal fun CompatibilityContent(
    tab: CompatibilityTab,
    chains: List<ChainFormats>,
    walletLevel: List<CompatibleWallet>,
    directory: List<CompatibleWallet>,
    onTabSelected: (CompatibilityTab) -> Unit,
    onBack: () -> Unit,
    onSubmitWallet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = SignerTheme.reduceMotion
    SettingsPage(title = stringResource(R.string.compatibility), onBack = onBack, modifier = modifier) { insets ->
        Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding())) {
            SegmentedControl(
                options = listOf(stringResource(R.string.formats), stringResource(R.string.wallets)),
                selectedIndex = tab.ordinal,
                onSelect = { onTabSelected(CompatibilityTab.entries[it]) },
                modifier = SectionFrame
                    .padding(vertical = 8.dp)
                    .testTag(CompatibilityTags.TAB),
                testTags = listOf(CompatibilityTags.FORMATS, CompatibilityTags.WALLETS),
            )
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val millis = if (reduceMotion) 0 else SEGMENT_FADE_MILLIS
                    fadeIn(tween(millis)) togetherWith fadeOut(tween(millis))
                },
                modifier = Modifier.weight(1f),
                label = "compatibility",
            ) { shown ->
                when (shown) {
                    CompatibilityTab.Formats -> FormatsList(chains, walletLevel, insets)
                    CompatibilityTab.Wallets -> WalletDirectory(directory, insets, onSubmitWallet)
                }
            }
        }
    }
}

/** The Formats segment (`SettingsTab.swift:169-218`). */
@Composable
private fun FormatsList(chains: List<ChainFormats>, walletLevel: List<CompatibleWallet>, insets: PaddingValues) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().testTag(CompatibilityTags.FORMATS_LIST),
        contentPadding = listContentPadding(insets),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        item(key = "intro") {
            Callout(stringResource(R.string.bittool_signer_uses_open_qr_standards_the_wallets_below), SectionFrame, tone = Tone.Info)
        }
        items(chains.size, key = { chains[it].chain.wire }) { index -> ChainSection(chains[index]) }
        item(key = "walletLevel") { WalletLevelSection(walletLevel) }
    }
}

@Composable
private fun ChainSection(formats: ChainFormats) {
    val chain = formats.chain
    val name = chain.localizedLongName()
    val formatsLabel = stringResource(R.string.qr_formats)
    val formatsValue = stringResource(chain.formatsRes())
    val signingLabel = stringResource(R.string.signing)
    val signingValue = stringResource(chain.signingRes())
    val signers = stringResource(R.string.watch_and_sign_by_qr)
    val watchers = stringResource(R.string.watch_only)
    GroupedSection(SectionFrame) {
        item(key = "title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(RowContentPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChainGlyph(chain, size = 36.dp)
                Text(name, style = SignerTheme.typography.headline, color = SignerTheme.colors.label, modifier = Modifier.semantics { heading() })
            }
        }
        item(key = "formats") { DetailLine(formatsLabel, formatsValue, Modifier.padding(RowContentPadding), monospaced = false) }
        item(key = "signing") { DetailLine(signingLabel, signingValue, Modifier.padding(RowContentPadding), monospaced = false) }
        if (formats.signers.isNotEmpty()) {
            item(key = "signers") {
                WalletGroup(signers, formats.signers, Modifier.padding(horizontal = 16.dp, vertical = 16.dp), icon = SignerIcons.QrCodeScanner)
            }
        }
        if (formats.watchers.isNotEmpty()) {
            item(key = "watchers") {
                WalletGroup(watchers, formats.watchers, Modifier.padding(horizontal = 16.dp, vertical = 16.dp), icon = SignerIcons.Visibility)
            }
        }
    }
}

/** "Wallet Level · all chains" (`SettingsTab.swift:183-194`). */
@Composable
private fun WalletLevelSection(walletLevel: List<CompatibleWallet>) {
    val title = stringResource(R.string.wallet_level_all_chains)
    val formatLabel = stringResource(R.string.qr_format)
    val formatValue = stringResource(R.string.settings_wallet_level_format, stringResource(R.string.settings_wallet_level_token))
    val worksWith = stringResource(R.string.works_with)
    GroupedSection(SectionFrame) {
        item(key = "title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(RowContentPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(SignerIcons.GridView, contentDescription = null, tint = SignerTheme.colors.accent, modifier = Modifier.size(26.dp))
                }
                Text(title, style = SignerTheme.typography.headline, color = SignerTheme.colors.label, modifier = Modifier.semantics { heading() })
            }
        }
        item(key = "format") { DetailLine(formatLabel, formatValue, Modifier.padding(RowContentPadding), monospaced = false) }
        item(key = "worksWith") {
            WalletGroup(worksWith, walletLevel, Modifier.padding(horizontal = 16.dp, vertical = 16.dp), icon = SignerIcons.QrCodeScanner)
        }
    }
}

/** The verbatim protocol tokens of a chain's "QR formats" line. */
private fun Chain.formatsRes(): Int = when (this) {
    Chain.Bitcoin -> R.string.settings_formats_bitcoin
    Chain.Evm -> R.string.settings_formats_evm
    Chain.Tron -> R.string.settings_formats_tron
    Chain.Solana -> R.string.settings_formats_solana
}

/** The translated "Signing" prose of a chain. */
private fun Chain.signingRes(): Int = when (this) {
    Chain.Bitcoin -> R.string.psbt_v0_v2_single_sig_legacy_nested_native_segwit
    Chain.Evm -> R.string.legacy_eip_2930_and_eip_1559_transactions_personal
    Chain.Tron -> R.string.trx_and_trc_20_transfers_contract_calls_permission
    Chain.Solana -> R.string.legacy_and_v0_transactions_with_static_accounts_and
}

private const val SEGMENT_FADE_MILLIS = 200
