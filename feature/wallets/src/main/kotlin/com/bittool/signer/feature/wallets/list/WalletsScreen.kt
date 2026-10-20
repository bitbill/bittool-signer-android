package com.bittool.signer.feature.wallets.list

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.EmptyState
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.TonalPillButton
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.designsystem.window.rememberWindowGateReleased
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.ui.logo.NetworkLogoStack
import com.bittool.signer.core.ui.wallet.WalletAvatar
import com.bittool.signer.core.ui.wallet.isolate
import com.bittool.signer.feature.wallets.common.IconAction
import com.bittool.signer.feature.wallets.common.WalletIdentity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Callbacks of the Wallets tab: pushes onto the Wallets stack (the NavHost glue owns the [androidx.navigation.NavController]). */
data class WalletsActions(
    val onOpenWallet: (WalletId) -> Unit = {},
    val onPair: (WalletId) -> Unit = {},
    val onAddresses: (WalletId) -> Unit = {},
    val onScan: () -> Unit = {},
    val onAddWallet: (SetupIntent) -> Unit = {},
)

/**
 * The Wallets tab root (inventory row 26, iOS `WalletsTab`, wallets-pairing.md §4): a large collapsing "Wallets" title
 * with "Scan" and the "Add Wallet" menu, then one card per wallet in vault order and the "Keys and signing stay on this
 * device." footer. Reselecting the tab scrolls back to the top.
 */
@Composable
fun WalletsScreen(
    onOpenWallet: (WalletId) -> Unit,
    onPair: (WalletId) -> Unit,
    onAddresses: (WalletId) -> Unit,
    viewModel: WalletsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WalletsContent(
        uiState = uiState,
        actions = WalletsActions(
            onOpenWallet = onOpenWallet,
            onPair = onPair,
            onAddresses = onAddresses,
            onScan = viewModel::scan,
            onAddWallet = viewModel::addWallet,
        ),
        scrollToTop = viewModel.reselections,
    )
}

/**
 * The tab content. Every emission of [scrollToTop] (the Wallets tab tapped while shown) scrolls the list back to the
 * first card and expands the large "Wallets" title again, like an iOS tab bar tap. A programmatic scroll does not go
 * through nested scrolling, so the collapsed bar is expanded explicitly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletsContent(
    uiState: WalletsUiState,
    actions: WalletsActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    topBarState: TopAppBarState = rememberTopAppBarState(),
    scrollToTop: Flow<*> = emptyFlow<Unit>(),
) {
    val colors = SignerTheme.colors
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    LaunchedEffect(scrollToTop, listState, topBarState) {
        scrollToTop.collect {
            coroutineScope {
                launch { listState.animateScrollToItem(0) }
                launch { topBarState.expand() }
            }
        }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.page,
        topBar = {
            SignerTopAppBar(
                title = stringResource(R.string.wallets),
                navigation = NavIcon.None,
                large = true,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconAction(
                        icon = SignerIcons.QrCodeScanner,
                        label = stringResource(R.string.scan),
                        onClick = actions.onScan,
                        modifier = Modifier.testTag("wallets.scan"),
                    )
                    AddWalletMenu(onAddWallet = actions.onAddWallet)
                },
            )
        },
    ) { padding ->
        if (uiState.loaded && uiState.wallets.isEmpty()) {
            NoWallets(actions.onAddWallet, Modifier.padding(padding))
            return@Scaffold
        }
        val direction = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("wallets.list"),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + SignerMetrics.pagePadding,
                end = padding.calculateEndPadding(direction) + SignerMetrics.pagePadding,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + SignerMetrics.listBottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(SignerMetrics.stack),
        ) {
            items(uiState.wallets, key = { it.id.toString() }, contentType = { "wallet" }) { wallet ->
                WalletCard(
                    wallet = wallet,
                    onOpen = { actions.onOpenWallet(wallet.id) },
                    onPair = { actions.onPair(wallet.id) },
                    onAddresses = { actions.onAddresses(wallet.id) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "footer", contentType = "footer") { KeysFooter() }
        }
    }
}

/** Expands a collapsed large top bar (exit-until-collapsed) without bounce. */
@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun TopAppBarState.expand() {
    val from = heightOffset
    if (from != 0f) animate(from, 0f, animationSpec = SignerMotion.smooth(0.35)) { value, _ -> heightOffset = value }
    contentOffset = 0f
}

/** Toolbar "Add Wallet" menu (inventory row 27, `WalletsTab.swift:36-43`), tag `wallets.add`. */
@Composable
private fun AddWalletMenu(onAddWallet: (SetupIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconAction(
            icon = SignerIcons.Add,
            label = stringResource(R.string.add_wallet),
            onClick = { expanded = true },
            modifier = Modifier.testTag("wallets.add"),
        )
        // A popup window: it waits for the window gate like every other window (ARCHITECTURE §1.10).
        if (expanded && rememberWindowGateReleased()) {
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }, containerColor = SignerTheme.colors.elevatedCard) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.create_new_wallet)) },
                    leadingIcon = { Icon(SignerIcons.Add, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAddWallet(SetupIntent.Create)
                    },
                    modifier = Modifier.testTag("wallets.add.create"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.import_recovery_phrase)) },
                    leadingIcon = { Icon(SignerIcons.Download, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAddWallet(SetupIntent.Restore)
                    },
                    modifier = Modifier.testTag("wallets.add.import"),
                )
            }
        }
    }
}

/**
 * One wallet (iOS `WalletCard`, `WalletsTab.swift:69-141`): identity on top (opens the wallet page), quick public
 * actions below. "There is no 'current' or 'default' wallet; every card is equal and self-contained."
 */
@Composable
internal fun WalletCard(
    wallet: WalletIdentity,
    onOpen: () -> Unit,
    onPair: () -> Unit,
    onAddresses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val id = wallet.id.toString()
    Column(
        modifier
            .readableWidth()
            .clip(SignerShapes.card)
            .background(colors.card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(R.string.shows_wallet_details),
                    role = Role.Button,
                    indication = ripple(),
                    interactionSource = null,
                    onClick = onOpen,
                )
                .testTag("wallet.card.$id")
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletAvatar(identity = wallet.avatarIdentity, name = wallet.name, size = 50.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = wallet.name,
                    style = typography.headline,
                    color = colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.fingerprint_x, fingerprintText(wallet.fingerprint)),
                    style = typography.caption.monospaced(),
                    color = colors.secondaryLabel,
                )
                if (wallet.ready) NetworkLogoStack(Modifier.padding(top = 2.dp), size = 16.dp)
            }
            Icon(SignerIcons.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(18.dp))
        }
        if (wallet.duplicate) {
            Callout(
                text = stringResource(R.string.another_wallet_has_the_same_key_identity_requests_it),
                tone = Tone.Warning,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        Box(
            Modifier
                .padding(start = 16.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.opaqueSeparator),
        )
        if (wallet.ready) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TonalPillButton(
                    text = stringResource(R.string.pair),
                    icon = SignerIcons.QrCode,
                    onClick = onPair,
                    modifier = Modifier.weight(1f).testTag("wallet.connect.$id"),
                )
                TonalPillButton(
                    text = stringResource(R.string.addresses),
                    icon = SignerIcons.ListAlt,
                    onClick = onAddresses,
                    modifier = Modifier.weight(1f).testTag("wallet.addresses.$id"),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SignerMetrics.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .testTag("wallet.repair.$id")
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SignerIcons.Build, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.repair_wallet_data_2),
                    style = typography.subheadline.semibold(),
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The "Fingerprint %@" value: the upper-case fingerprint, or "Setup required" (V on iOS) without public data. In a
 * right-to-left interface the value is wrapped in Unicode isolates so it stays left to right inside the translated
 * sentence; left-to-right text stays plain (the e2e suite matches "Fingerprint 73C5DA0A" literally).
 */
@Composable
internal fun fingerprintText(fingerprint: String?): String {
    val value = fingerprint ?: stringResource(R.string.ui_setup_required)
    return if (LocalLayoutDirection.current == LayoutDirection.Rtl) isolate(value) else value
}

@Composable
private fun KeysFooter() {
    val colors = SignerTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SignerIcons.ShieldLock, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.keys_and_signing_stay_on_this_device),
            style = SignerTheme.typography.footnote,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The list loaded empty. iOS never shows this (zero wallets → Welcome, `RootView.swift:13-18`); on Android it can be on
 * screen for the frames between deleting the last wallet and the stage switch, so it offers the same two ways in.
 */
@Composable
private fun NoWallets(onAddWallet: (SetupIntent) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = SignerIcons.Wallet,
            title = stringResource(R.string.add_wallet),
            description = stringResource(R.string.keys_and_signing_stay_on_this_device),
            action = {
                Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(SignerMetrics.barGap)) {
                    PrimaryButton(
                        text = stringResource(R.string.create_new_wallet),
                        onClick = { onAddWallet(SetupIntent.Create) },
                        icon = SignerIcons.Add,
                    )
                    SecondaryButton(
                        text = stringResource(R.string.import_recovery_phrase),
                        onClick = { onAddWallet(SetupIntent.Restore) },
                        icon = SignerIcons.Download,
                    )
                }
            },
        )
    }
}
