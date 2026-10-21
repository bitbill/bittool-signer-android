package com.bittool.signer.feature.wallets.addresses

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.designsystem.window.rememberWindowGateReleased
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.ui.address.AddressText
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import com.bittool.signer.core.ui.pairing.ChainPicker
import com.bittool.signer.core.ui.pairing.ProfileMenu
import com.bittool.signer.core.ui.text.Callout
import com.bittool.signer.core.ui.text.localizedTitle
import com.bittool.signer.feature.wallets.common.GroupedFooter
import com.bittool.signer.feature.wallets.common.GroupedHeader
import com.bittool.signer.feature.wallets.common.GroupedLazyRow
import com.bittool.signer.feature.wallets.common.RowPosition

/** What the Addresses page can ask for. */
data class AddressesActions(
    val onBack: () -> Unit = {},
    val onSelectChain: (Chain) -> Unit = {},
    val onSelectBitcoinProfile: (String) -> Unit = {},
    val onShowAddress: (PublicAccount) -> Unit = {},
    val onDismissAddress: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
)

/**
 * Receive addresses (inventory rows 37–38b, iOS `AddressesView`, wallets-pairing.md §6): the chain picker, the Bitcoin
 * address type, then the derived rows, 20 per page (the next page loads when the last row appears), each opening its
 * QR sheet; a long press offers "Copy Address" and "Show QR Code".
 */
@Composable
fun AddressesScreen(onBack: () -> Unit, onClose: () -> Unit, viewModel: AddressesViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.gone) { if (uiState.gone) onClose() }
    AddressesContent(
        uiState = uiState,
        actions = AddressesActions(
            onBack = onBack,
            onSelectChain = viewModel::selectChain,
            onSelectBitcoinProfile = viewModel::selectBitcoinProfile,
            onShowAddress = viewModel::showAddress,
            onDismissAddress = viewModel::dismissAddress,
            onLoadMore = viewModel::loadMore,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressesContent(uiState: AddressesUiState, actions: AddressesActions, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.page,
        topBar = {
            SignerTopAppBar(
                title = stringResource(R.string.addresses),
                navigation = NavIcon.Back,
                onNavigate = actions.onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val chain = uiState.chain
        val labelWidth = if (chain == Chain.Solana) rememberPresetLabelWidth() else null
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("addresses.list"),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + SignerMetrics.pagePadding,
                end = padding.calculateEndPadding(direction) + SignerMetrics.pagePadding,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + SignerMetrics.listBottomInset,
            ),
        ) {
            item(key = "controls", contentType = "controls") {
                Column(Modifier.readableWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChainPicker(selection = chain, onSelect = actions.onSelectChain, modifier = Modifier.fillMaxWidth())
                    if (chain == Chain.Bitcoin) {
                        val presets = AccountProfiles.bitcoinSingle
                        val selection = presets.firstOrNull { it.id == uiState.btcProfile } ?: presets.first()
                        val titles = presets.associate { it.id to it.localizedTitle() }
                        ProfileMenu(
                            title = stringResource(R.string.address_type),
                            options = presets,
                            selection = selection,
                            label = { titles[it.id] ?: it.title },
                            detail = { it.detail },
                            onSelect = { actions.onSelectBitcoinProfile(it.id) },
                        )
                    }
                }
            }
            item(key = "header", contentType = "header") { GroupedHeader(sectionTitle(uiState), Modifier.readableWidth()) }
            when {
                uiState.loaded -> {
                    val count = uiState.accounts.size + if (uiState.loadingMore) 1 else 0
                    itemsIndexed(uiState.accounts, key = { _, account -> account.id }, contentType = { _, _ -> "address" }) { index, account ->
                        GroupedLazyRow(RowPosition.of(index, count), Modifier.readableWidth()) {
                            AddressRow(
                                account = account,
                                chain = chain,
                                labelWidth = labelWidth,
                                onOpen = { actions.onShowAddress(account) },
                            )
                        }
                        // iOS `.onAppear` of the last row asks for the next page.
                        if (index == uiState.accounts.lastIndex) LaunchedEffect(account.id) { actions.onLoadMore() }
                    }
                    if (uiState.loadingMore) {
                        item(key = "more", contentType = "spinner") {
                            GroupedLazyRow(RowPosition.of(count - 1, count), Modifier.readableWidth()) { Spinner(Modifier.padding(vertical = 12.dp)) }
                        }
                    }
                }
                uiState.error != null -> item(key = "error", contentType = "error") {
                    Callout(text = uiState.error, tone = Tone.Danger, modifier = Modifier.readableWidth())
                }
                else -> item(key = "loading", contentType = "spinner") {
                    GroupedLazyRow(RowPosition.Single, Modifier.readableWidth()) { Spinner(Modifier.padding(vertical = 20.dp)) }
                }
            }
            item(key = "footer", contentType = "footer") { GroupedFooter(sectionFooter(chain), Modifier.readableWidth()) }
        }
    }
    uiState.selected?.let { account ->
        AddressDetailSheet(account = account, title = addressTitle(account, chain = account.chain), onDismiss = actions.onDismissAddress)
    }
}

/** `AddressesView.swift:73-80`. */
@Composable
private fun sectionTitle(uiState: AddressesUiState): String = when (uiState.chain) {
    Chain.Bitcoin -> stringResource(
        R.string.receive_x,
        AccountProfiles.bitcoinSingle.firstOrNull { it.id == uiState.btcProfile }?.localizedTitle().orEmpty(),
    )
    Chain.Evm -> stringResource(R.string.receive_all_evm_networks)
    Chain.Tron -> stringResource(R.string.receive_trx_and_trc_20_tokens)
    Chain.Solana -> stringResource(R.string.accounts_by_derivation_path)
}

/** `AddressesView.swift:82-89`. */
@Composable
private fun sectionFooter(chain: Chain): String = stringResource(
    when (chain) {
        Chain.Bitcoin -> R.string.showing_receive_addresses_multisig_addresses_need_every
        Chain.Evm -> R.string.one_address_works_on_ethereum_bnb_smart_chain_arbitrum
        Chain.Tron -> R.string.every_tron_receive_index_is_available_to_export_a
        Chain.Solana -> R.string.solana_wallets_use_different_derivation_paths_pick_the
    },
)

/**
 * The address sheet's title (`AddressesView.swift:91-100`): "Standard account" for Solana presets, "Bitcoin address #0"
 * otherwise (the chain name is verbatim).
 */
@Composable
internal fun addressTitle(account: PublicAccount, chain: Chain): String =
    if (chain == Chain.Solana) {
        stringResource(R.string.x_account, AccountProfiles.solanaPreset(account.path)?.localizedTitle() ?: Chain.Solana.displayName)
    } else {
        stringResource(R.string.x_address_x, chain.displayName, account.index)
    }

/**
 * One address (iOS `AddressRow`, `AddressesView.swift:143-168`): `#index` (Solana: the preset name), the grouped address
 * and a QR glyph; the whole row opens the sheet. A long press offers "Copy Address" (no toast, as iOS) and "Show QR
 * Code", also offered to TalkBack as custom actions. Tag `address.row.<index>`. [labelWidth] fixes the label column
 * (Solana's preset names); null keeps iOS `minWidth: 34`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddressRow(account: PublicAccount, chain: Chain, labelWidth: Dp?, onOpen: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val copyLabel = stringResource(R.string.copy_address)
    val showLabel = stringResource(R.string.show_qr_code)
    val label = if (chain == Chain.Solana) {
        AccountProfiles.solanaPreset(account.path)?.localizedTitle().orEmpty()
    } else {
        "#" + account.index
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .combinedClickable(
                    interactionSource = null,
                    indication = ripple(),
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.shows_qr_code_and_derivation_path),
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onClick = onOpen,
                )
                .semantics(mergeDescendants = true) {
                    customActions = listOf(
                        CustomAccessibilityAction(copyLabel) { SignerClipboard.copy(context, account.address) },
                        CustomAccessibilityAction(showLabel) {
                            onOpen()
                            true
                        },
                    )
                }
                .testTag("address.row.${account.index}")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = typography.caption.addressLabel(),
                color = colors.secondaryLabel,
                modifier = if (labelWidth != null) Modifier.width(labelWidth) else Modifier.widthIn(min = LABEL_MIN_WIDTH),
                maxLines = 1,
            )
            AddressText(account.address, Modifier.weight(1f), style = typography.footnote)
            Icon(SignerIcons.QrCode, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        // A popup window: it waits for the window gate like every other window (ARCHITECTURE §1.10).
        if (menu && rememberWindowGateReleased()) {
            DropdownMenu(expanded = true, onDismissRequest = { menu = false }, containerColor = colors.elevatedCard) {
                DropdownMenuItem(
                    text = { Text(copyLabel) },
                    leadingIcon = { Icon(SignerIcons.Copy, contentDescription = null) },
                    onClick = {
                        menu = false
                        SignerClipboard.copy(context, account.address)
                    },
                )
                DropdownMenuItem(
                    text = { Text(showLabel) },
                    leadingIcon = { Icon(SignerIcons.QrCode, contentDescription = null) },
                    onClick = {
                        menu = false
                        onOpen()
                    },
                )
            }
        }
    }
}

/** The row label's style (iOS `.caption.weight(.semibold).monospacedDigit()`). */
private fun TextStyle.addressLabel(): TextStyle = semibold().monospacedDigit()

/** iOS `frame(minWidth: 34, alignment: .leading)` of the row label. */
private val LABEL_MIN_WIDTH: Dp = 34.dp

/**
 * The label column of the Solana rows (QA wallets-13): the widest preset name (Standard, Trust, Legacy in the app
 * language and text size), so every address starts at the same x. With iOS `minWidth: 34` alone each address moved
 * with the width of its name (iOS has the same misalignment; the index labels of the other chains stay as iOS draws
 * them).
 */
@Composable
private fun rememberPresetLabelWidth(): Dp {
    val style = SignerTheme.typography.caption.addressLabel()
    val labels = AccountProfiles.solana.map { it.localizedTitle() }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(labels, style, density, measurer) {
        labels.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
    }
    return maxOf(with(density) { widest.toDp() }, LABEL_MIN_WIDTH)
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SignerTheme.colors.secondaryLabel, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}
