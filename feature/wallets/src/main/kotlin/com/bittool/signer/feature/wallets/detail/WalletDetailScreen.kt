package com.bittool.signer.feature.wallets.detail

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.IconRowDividerInset
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.NavRow
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import com.bittool.signer.core.ui.logo.ChainGlyph
import com.bittool.signer.core.ui.logo.ChainLogo
import com.bittool.signer.core.ui.text.asString
import com.bittool.signer.core.ui.text.currentLocale
import com.bittool.signer.core.ui.text.localizedLongName
import com.bittool.signer.core.ui.wallet.WalletAvatar
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.list.fingerprintText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Where the wallet page leads (pushes and modal pages; the NavHost glue owns the controller). */
data class WalletDetailActions(
    val onBack: () -> Unit = {},
    val onConnect: (Chain) -> Unit = {},
    val onConnectWallet: () -> Unit = {},
    val onAddresses: () -> Unit = {},
    val onRecoveryPhrase: () -> Unit = {},
    val onChangePassword: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onRepair: () -> Unit = {},
)

/**
 * The wallet page (inventory rows 28–31, iOS `WalletDetailView`, wallets-pairing.md §5): identity header, duplicate and
 * repair notices, the four chains and Wallet Level under "Pair", receive addresses, security, rename and delete. Closes
 * itself when the wallet leaves the list.
 *
 * [passwordChanged] is the Change Password page's result (read by the destination glue from this page's back-stack
 * entry): the "Password Changed" toast shows once, then [onPasswordChangedShown] clears it.
 */
@Composable
fun WalletDetailScreen(
    actions: WalletDetailActions,
    onClose: () -> Unit,
    passwordChanged: Boolean = false,
    onPasswordChangedShown: () -> Unit = {},
    viewModel: WalletDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = rememberStatusToastState()
    val context = LocalContext.current
    val copied = stringResource(R.string.copied)
    val passwordChangedText = stringResource(R.string.password_changed)
    LaunchedEffect(uiState.gone) { if (uiState.gone) onClose() }
    LaunchedEffect(passwordChanged) {
        if (passwordChanged) {
            toast.show(passwordChangedText)
            onPasswordChangedShown()
        }
    }
    WalletDetailContent(
        uiState = uiState,
        actions = actions,
        toast = toast,
        onCopyFingerprint = {
            val fingerprint = viewModel.fingerprintToCopy()
            if (fingerprint != null && SignerClipboard.copy(context, fingerprint)) toast.show(copied)
        },
        onRename = viewModel::startRename,
    )
    if (uiState.renaming) {
        RenameWalletDialog(
            currentName = uiState.identity?.name.orEmpty(),
            onSave = viewModel::rename,
            onCancel = viewModel::cancelRename,
        )
    }
    uiState.renameError?.let { error ->
        UnableToRenameDialog(message = error.asString(), onDismiss = viewModel::dismissRenameError)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailContent(
    uiState: WalletDetailUiState,
    actions: WalletDetailActions,
    toast: StatusToastState,
    onCopyFingerprint: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val identity = uiState.identity
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.page,
        topBar = {
            SignerTopAppBar(
                title = identity?.name ?: stringResource(R.string.wallet),
                navigation = NavIcon.Back,
                onNavigate = actions.onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            val direction = LocalLayoutDirection.current
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(direction) + SignerMetrics.pagePadding,
                    end = padding.calculateEndPadding(direction) + SignerMetrics.pagePadding,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + SignerMetrics.listBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            ) {
                item(key = "header") { DetailHeader(identity, onCopyFingerprint, Modifier.readableWidth()) }
                if (identity?.duplicate == true) {
                    item(key = "duplicate") {
                        Callout(
                            text = stringResource(R.string.another_saved_wallet_uses_the_same_recovery_phrase_and),
                            title = stringResource(R.string.duplicate_wallet),
                            tone = Tone.Warning,
                            modifier = Modifier.readableWidth(),
                        )
                    }
                }
                if (identity != null && !identity.ready) {
                    item(key = "repair") { RepairSection(actions.onRepair, Modifier.readableWidth()) }
                } else if (identity != null) {
                    item(key = "pair") { PairSection(actions, Modifier.readableWidth()) }
                    item(key = "addresses") {
                        GroupedSection(
                            modifier = Modifier.readableWidth(),
                            footer = stringResource(R.string.derived_on_this_device_from_public_keys_balances_are),
                        ) {
                            item(dividerInset = IconRowDividerInset) {
                                NavRow(
                                    title = stringResource(R.string.receive_addresses),
                                    icon = SignerIcons.ListAlt,
                                    onClick = actions.onAddresses,
                                    modifier = Modifier.testTag("detail.addresses"),
                                )
                            }
                        }
                    }
                }
                if (identity != null) {
                    item(key = "security") { SecuritySection(actions, Modifier.readableWidth()) }
                    item(key = "rename") {
                        GroupedSection(Modifier.readableWidth()) {
                            item {
                                NavRow(
                                    title = stringResource(R.string.rename_wallet),
                                    icon = SignerIcons.Edit,
                                    onClick = onRename,
                                    showChevron = false,
                                    modifier = Modifier.testTag("detail.rename"),
                                )
                            }
                        }
                    }
                    item(key = "delete") {
                        GroupedSection(
                            modifier = Modifier.readableWidth(),
                            footer = stringResource(R.string.deleting_removes_this_wallet_from_bittool_signer_only),
                        ) {
                            item {
                                NavRow(
                                    title = stringResource(R.string.delete_wallet),
                                    icon = SignerIcons.Delete,
                                    onClick = actions.onDelete,
                                    destructive = true,
                                    modifier = Modifier.testTag("detail.delete"),
                                )
                            }
                        }
                    }
                }
            }
            StatusToastHost(toast)
        }
    }
}

/** `WalletDetailView.swift:134-160`: avatar 76, name, the fingerprint (tap to copy) and the date it was added. */
@Composable
private fun DetailHeader(identity: WalletIdentity?, onCopyFingerprint: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val locale = currentLocale()
    val added = identity?.let { remember(it.createdAtMillis, locale) { formatAddedDate(it.createdAtMillis, locale) } }
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WalletAvatar(identity = identity?.avatarIdentity.orEmpty(), name = identity?.name.orEmpty(), size = 76.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = identity?.name ?: stringResource(R.string.wallet),
                style = typography.title2.bold(),
                color = colors.label,
                textAlign = TextAlign.Center,
            )
            val canCopy = identity?.fingerprint != null
            Text(
                text = stringResource(R.string.fingerprint_x, fingerprintText(identity?.fingerprint)),
                style = typography.footnote.monospaced(),
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .heightIn(min = SignerMetrics.minTouchTarget)
                    .clickable(
                        enabled = canCopy,
                        onClickLabel = stringResource(R.string.copies_the_master_fingerprint),
                        role = Role.Button,
                        indication = ripple(bounded = false),
                        interactionSource = null,
                        onClick = onCopyFingerprint,
                    )
                    .testTag("detail.fingerprint")
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            )
            if (added != null) {
                Text(
                    text = stringResource(R.string.added_x, added),
                    style = typography.footnote,
                    color = colors.tertiaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** iOS `created.formatted(date: .abbreviated, time: .omitted)` ("Oct 20, 2026") in the app locale. */
internal fun formatAddedDate(createdAtMillis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        .format(Instant.ofEpochMilli(createdAtMillis).atZone(zone).toLocalDate())

@Composable
private fun RepairSection(onRepair: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Callout(
            text = stringResource(R.string.this_wallets_public_data_is_missing_or_damaged_your),
            title = stringResource(R.string.repair_needed),
            tone = Tone.Warning,
        )
        GroupedSection {
            item(dividerInset = IconRowDividerInset) {
                NavRow(
                    title = stringResource(R.string.repair_wallet_data),
                    icon = SignerIcons.Build,
                    onClick = onRepair,
                    modifier = Modifier.testTag("detail.repair"),
                )
            }
        }
    }
}

@Composable
private fun PairSection(actions: WalletDetailActions, modifier: Modifier = Modifier) {
    GroupedSection(
        modifier = modifier,
        header = stringResource(R.string.pair),
        footer = stringResource(R.string.export_public_keys_to_a_watch_only_wallet_such_as),
    ) {
        Chain.entries.forEach { chain ->
            item(key = chain.wire, dividerInset = GLYPH_ROW_DIVIDER_INSET) {
                GlyphRow(
                    title = chain.localizedLongName(),
                    subtitle = stringResource(chainSubtitle(chain)),
                    onClick = { actions.onConnect(chain) },
                    modifier = Modifier.testTag("detail.connect." + chain.wire),
                ) { ChainGlyph(chain, size = 32.dp) }
            }
        }
        item(key = "wallet", dividerInset = GLYPH_ROW_DIVIDER_INSET) {
            GlyphRow(
                title = stringResource(R.string.wallet_level),
                subtitle = stringResource(R.string.one_qr_code_for_all_four_chains),
                onClick = actions.onConnectWallet,
                modifier = Modifier.testTag("detail.connect.wallet"),
            ) { WalletLevelGlyph() }
        }
    }
}

@Composable
private fun SecuritySection(actions: WalletDetailActions, modifier: Modifier = Modifier) {
    GroupedSection(modifier = modifier, header = stringResource(R.string.security)) {
        item(dividerInset = IconRowDividerInset) {
            NavRow(
                title = stringResource(R.string.show_recovery_phrase),
                icon = SignerIcons.Key,
                onClick = actions.onRecoveryPhrase,
                modifier = Modifier.testTag("detail.recoveryPhrase"),
            )
        }
        item(dividerInset = IconRowDividerInset) {
            NavRow(
                title = stringResource(R.string.change_password),
                icon = SignerIcons.LockReset,
                onClick = actions.onChangePassword,
                modifier = Modifier.testTag("detail.changePassword"),
            )
        }
    }
}

/** `ChainRow` subtitles (`WalletDetailView.swift:196-203`). */
internal fun chainSubtitle(chain: Chain): Int = when (chain) {
    Chain.Bitcoin -> R.string.single_sig_and_multisig_accounts
    Chain.Evm -> R.string.ethereum_bnb_chain_arbitrum_base_and_more
    Chain.Tron -> R.string.any_address_index
    Chain.Solana -> R.string.standard_trust_and_legacy_paths
}

/**
 * A navigation row led by a 32 dp chain mark (iOS `ChainRow` / `WalletLevelRow` in a `NavigationLink`): the `NavRow`
 * look (56 dp, ripple, mirrored chevron) with the title in body and the subtitle in caption, secondary.
 */
@Composable
private fun GlyphRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: @Composable () -> Unit,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(GLYPH_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title already names the chain: the logo is decorative here (TalkBack would read it twice).
        Box(Modifier.size(GLYPH_SIZE).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { glyph() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = typography.body, color = colors.label)
            Text(subtitle, style = typography.caption, color = colors.secondaryLabel)
        }
        Icon(SignerIcons.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(20.dp))
    }
}

/** `WalletLevelRow`'s 2 × 2 grid of 15 dp chain logos (Bitcoin, EVM / TRON, Solana), decorative. */
@Composable
private fun WalletLevelGlyph() {
    Column(
        modifier = Modifier.clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf(listOf(Chain.Bitcoin, Chain.Evm), listOf(Chain.Tron, Chain.Solana)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { row.forEach { ChainLogo(it, size = 15.dp) } }
        }
    }
}

private val SECTION_GAP: Dp = 20.dp
private val GLYPH_SIZE: Dp = 32.dp
private val GLYPH_GAP: Dp = 12.dp
private val GLYPH_ROW_DIVIDER_INSET: Dp = 16.dp + GLYPH_SIZE + GLYPH_GAP
