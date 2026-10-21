package com.bittool.signer.feature.wallets.detail

import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.bittool.signer.core.ui.security.openScreenLockSettings
import com.bittool.signer.core.ui.text.asString
import com.bittool.signer.core.ui.text.currentLocale
import com.bittool.signer.core.ui.text.localizedLongName
import com.bittool.signer.core.ui.wallet.WalletAvatar
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.common.ownsItsTouches
import com.bittool.signer.feature.wallets.common.undoesScrollBackOnShrink
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
 * Android-only (QA L4, production-04): Repair is offered only where it can work. A wallet whose device key is gone (the
 * screen lock was removed) says "This Wallet Can’t Be Unlocked" with "Remove This Wallet" and nothing else (no password
 * can help any of its other actions); without a screen lock the page says that one is needed first, with "Open
 * Settings".
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
    val activity = LocalActivity.current
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
        onOpenLockSettings = { openScreenLockSettings(activity ?: context) },
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
    onOpenLockSettings: () -> Unit = {},
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
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                // The Rename dialog's keyboard hides the tab bar, so the page grows and shrinks behind the dialog: near its
                // end, the rows come back to where they were (QA regression-04).
                modifier = Modifier.undoesScrollBackOnShrink(listState).fillMaxSize(),
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
                if (identity?.keyLost == true) {
                    item(key = "lostKey") { LostKeySection(actions.onDelete, Modifier.readableWidth()) }
                } else if (identity?.screenLockRequired == true) {
                    item(key = "screenLock") { ScreenLockSection(onOpenLockSettings, Modifier.readableWidth()) }
                } else if (identity != null && !identity.ready) {
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
                // A wallet that can never be unlocked has nothing else to offer: its one action is above.
                if (identity != null && !identity.keyLost) {
                    item(key = "security") { SecuritySection(actions, Modifier.readableWidth()) }
                    item(key = "rename") {
                        GroupedSection(Modifier.readableWidth()) {
                            item {
                                ActionRow(
                                    title = stringResource(R.string.rename_wallet),
                                    icon = SignerIcons.Edit,
                                    onClick = onRename,
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
                modifier = Modifier.ownsItsTouches(),
            )
            val canCopy = identity?.fingerprint != null
            // Laid out at the line's own height, so name, fingerprint and date stay 4 dp apart as on iOS (QA wallets-08).
            // Compose gives a clickable smaller than the minimum touch target the touches around it and reports 48 dp
            // bounds to accessibility services. The name and the date take their own touches, so that expansion only
            // covers the gaps and the sides and never the line above or below (QA regression-09). A wallet that no
            // repair can fix (QA L4) shows no "Fingerprint Setup required": the notice under the header says why.
            if (identity == null || identity.ready || identity.needsRepair) Text(
                text = stringResource(R.string.fingerprint_x, fingerprintText(identity?.fingerprint)),
                style = typography.footnote.monospaced(),
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable(
                        enabled = canCopy,
                        onClickLabel = stringResource(R.string.copies_the_master_fingerprint),
                        role = Role.Button,
                        indication = ripple(bounded = false),
                        interactionSource = null,
                        onClick = onCopyFingerprint,
                    )
                    .testTag("detail.fingerprint")
                    .padding(horizontal = 12.dp),
            )
            if (added != null) {
                Text(
                    text = stringResource(R.string.added_x, added),
                    style = typography.footnote,
                    color = colors.tertiaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.ownsItsTouches(),
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
                ActionRow(
                    title = stringResource(R.string.repair_wallet_data),
                    icon = SignerIcons.Build,
                    onClick = onRepair,
                    modifier = Modifier.testTag("detail.repair"),
                )
            }
        }
    }
}

/**
 * QA L4: the device key is gone, so neither the password nor Repair can help; the only way on is removing the wallet
 * (the Delete page's "Remove This Wallet", backlog B9) and importing its phrase again.
 */
@Composable
private fun LostKeySection(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Callout(
            text = stringResource(R.string.wallets_lost_key_message),
            title = stringResource(R.string.wallets_lost_key_title),
            tone = Tone.Danger,
            modifier = Modifier.testTag("detail.deviceKeyLost"),
        )
        GroupedSection {
            item(dividerInset = IconRowDividerInset) {
                NavRow(
                    title = stringResource(R.string.wallets_lost_key_remove),
                    icon = SignerIcons.Delete,
                    onClick = onRemove,
                    destructive = true,
                    modifier = Modifier.testTag("detail.removeLostKey"),
                )
            }
        }
    }
}

/** QA L4: without a secure lock screen no wallet can be unlocked or repaired; the person sets one first. */
@Composable
private fun ScreenLockSection(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Callout(
            text = stringResource(R.string.crypto_device_lock_required),
            title = stringResource(R.string.setup_screen_lock_required),
            tone = Tone.Warning,
            modifier = Modifier.testTag("detail.screenLockRequired"),
        )
        GroupedSection {
            item(dividerInset = IconRowDividerInset) {
                ActionRow(
                    title = stringResource(R.string.open_settings),
                    icon = SignerIcons.DeviceLock,
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("detail.openLockSettings"),
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
            ActionRow(
                title = stringResource(R.string.show_recovery_phrase),
                icon = SignerIcons.Key,
                onClick = actions.onRecoveryPhrase,
                modifier = Modifier.testTag("detail.recoveryPhrase"),
            )
        }
        item(dividerInset = IconRowDividerInset) {
            ActionRow(
                title = stringResource(R.string.change_password),
                icon = SignerIcons.LockReset,
                onClick = actions.onChangePassword,
                modifier = Modifier.testTag("detail.changePassword"),
            )
        }
    }
}

/**
 * A list button (iOS `Button { sheet = … } label: { Label(title, systemImage:) }` in the wallet page's `List`,
 * `WalletDetailView.swift:43-45, 75-93`; QA regression-14): icon and title in the accent tint and no chevron, because it
 * opens a security page or a dialog over the wallet and pushes nothing. The `NavRow` layout otherwise (56 dp, 24 dp icon,
 * ripple, `Role.Button`). "Delete Wallet" stays the destructive `NavRow`, which already has the iOS look.
 */
@Composable
private fun ActionRow(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Text(title, style = SignerTheme.typography.body, color = colors.accent, modifier = Modifier.weight(1f))
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
