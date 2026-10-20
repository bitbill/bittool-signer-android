package com.bittool.signer.feature.settings.reference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.core.ui.logo.GenericNetworkLogo
import com.bittool.signer.core.ui.logo.NetworkLogo
import com.bittool.signer.core.ui.wallet.isolate
import com.bittool.signer.feature.settings.SectionFrame
import com.bittool.signer.feature.settings.SectionGap
import com.bittool.signer.feature.settings.SettingsPage
import com.bittool.signer.feature.settings.LoadingRow
import com.bittool.signer.feature.settings.listContentPadding
import com.bittool.signer.feature.settings.rememberResumed
import com.bittool.signer.feature.settings.rememberResumedAction

/** Test tags of the directory (iOS identifiers `network.<key>`, `contracts.everyChain`). */
object NetworksTags {
    const val EVERY_CHAIN = "contracts.everyChain"
    const val LIST = "networks.list"

    fun network(key: String) = "network.$key"
}

/** "Networks, Tokens & Contracts" (inventory row 66). [onOpenNetwork] receives the grouping key (`evm:1`, `evm:*`). */
@Composable
fun NetworksScreen(
    onBack: () -> Unit,
    onOpenNetwork: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NetworksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NetworksContent(state, onBack = rememberResumed(onBack), onOpenNetwork = rememberResumedAction(onOpenNetwork), modifier = modifier)
}

@Composable
internal fun NetworksContent(state: NetworksUiState, onBack: () -> Unit, onOpenNetwork: (String) -> Unit, modifier: Modifier = Modifier) {
    SettingsPage(title = stringResource(R.string.networks_tokens_contracts), onBack = onBack, modifier = modifier) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding())
                .testTag(NetworksTags.LIST),
            contentPadding = listContentPadding(insets),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            item(key = "networks") { NetworksSection(state, onOpenNetwork) }
            if (state.everyChainContracts > 0) {
                item(key = "contracts") { ContractsSection(state, onOpenNetwork) }
            }
        }
    }
}

@Composable
private fun NetworksSection(state: NetworksUiState, onOpenNetwork: (String) -> Unit) {
    val bitcoin = stringResource(R.string.bitcoin)
    val bitcoinDetail = stringResource(R.string.btc_single_sig_and_multisig)
    val summaries = state.networks.map { networkSummary(it) }
    GroupedSection(
        modifier = SectionFrame,
        header = stringResource(R.string.networks),
        footer = if (state.loading) null else stringResource(R.string.recognized_tokens_are_shown_with_their_name_icon_and, isolate(state.registryVersion)),
    ) {
        item(key = "bitcoin", dividerInset = LOGO_ROW_DIVIDER_INSET) {
            NetworkRow(brand = NetworkBrand.Bitcoin, title = bitcoin, detail = bitcoinDetail, onClick = null)
        }
        if (state.loading) item(key = "loading") { LoadingRow() }
        state.networks.forEachIndexed { index, network ->
            item(key = network.key, dividerInset = LOGO_ROW_DIVIDER_INSET) {
                NetworkRow(
                    brand = network.brand,
                    title = network.name,
                    detail = summaries[index],
                    onClick = { onOpenNetwork(network.chain) },
                    modifier = Modifier.testTag(NetworksTags.network(network.key)),
                )
            }
        }
    }
}

@Composable
private fun ContractsSection(state: NetworksUiState, onOpenNetwork: (String) -> Unit) {
    val title = stringResource(R.string.all_evm_networks)
    val detail = stringResource(R.string.x_contracts_at_the_same_address_on_every_network, state.everyChainContracts)
    GroupedSection(
        modifier = SectionFrame,
        header = stringResource(R.string.known_public_contracts),
        footer = stringResource(R.string.x_contract_addresses_published_by_the_projects, state.contractCount, isolate(state.contractsUpdated)),
    ) {
        item(key = "everyChain") {
            NetworkRow(
                brand = null,
                title = title,
                detail = detail,
                onClick = { onOpenNetwork(ReferenceData.EVERY_EVM_CHAIN) },
                modifier = Modifier.testTag(NetworksTags.EVERY_CHAIN),
                detailLines = 2,
            )
        }
    }
}

/**
 * "ETH · 1008 tokens · 329 contracts", leaving out whichever is zero (iOS `summary(for:)`): the native symbol
 * verbatim, each count in the interface language (ungrouped like iOS `%lld`).
 */
@Composable
@ReadOnlyComposable
private fun networkSummary(network: NetworkRowModel): String {
    val parts = mutableListOf(network.nativeSymbol)
    if (network.tokens > 0) parts += stringResource(R.string.x_tokens_2, network.tokens)
    if (network.contracts > 0) parts += stringResource(R.string.x_contracts_2, network.contracts)
    return parts.joinToString(" · ")
}

/** A network row: the 32 dp logo (brand or generic), the name and its summary; navigable when [onClick] is set. */
@Composable
private fun NetworkRow(
    brand: NetworkBrand?,
    title: String,
    detail: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    detailLines: Int = 1,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val clickable = if (onClick != null) {
        Modifier.clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brand != null) NetworkLogo(brand, size = 32.dp) else GenericNetworkLogo(size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = typography.body, color = colors.label)
            Text(
                text = detail,
                style = typography.caption,
                color = colors.secondaryLabel,
                maxLines = if (SignerTheme.isAccessibilityTextSize) detailLines + 1 else detailLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Icon(SignerIcons.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(20.dp))
        }
    }
}

/** Hairline inset past the 32 dp logo (16 + 32 + 14). */
private val LOGO_ROW_DIVIDER_INSET = 62.dp
