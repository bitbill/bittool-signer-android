package com.bittool.signer.feature.wallets.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SegmentedControl
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import com.bittool.signer.feature.wallets.common.IconAction

/** What the Pair page can ask for. */
data class ConnectActions(
    val onBack: () -> Unit = {},
    val onSubmitWallet: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onSelectLevel: (PairingLevel) -> Unit = {},
    val onSelectChain: (Chain) -> Unit = {},
    val onSelectMultisig: (Boolean) -> Unit = {},
    val onSelectBitcoinProfile: (String) -> Unit = {},
    val onSelectSolanaProfile: (String) -> Unit = {},
    val onSelectOutputMode: (String) -> Unit = {},
    val onSelectTronIndex: (Long) -> Unit = {},
)

/**
 * The Pair page (inventory rows 39–43, iOS `ConnectView`, wallets-pairing.md §7): "Wallet Level" (one animated QR for
 * all four chains, BitTool Signer's own format) or "Coin Level" (one chain in its standard third-party format), the
 * verification details to compare in the other wallet, and the wallets documented to read each export. "Copy QR Data"
 * copies exactly the text of the code on screen.
 */
@Composable
fun ConnectScreen(onBack: () -> Unit, onSubmitWallet: () -> Unit, onClose: () -> Unit, viewModel: ConnectViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = rememberStatusToastState()
    val context = LocalContext.current
    val copied = stringResource(R.string.copied)
    LaunchedEffect(uiState.gone) { if (uiState.gone) onClose() }
    ConnectContent(
        uiState = uiState,
        toast = toast,
        actions = ConnectActions(
            onBack = onBack,
            onSubmitWallet = onSubmitWallet,
            onCopy = {
                val text = viewModel.uiState.value.copyText
                if (text != null && SignerClipboard.copy(context, text)) toast.show(copied)
            },
            onSelectLevel = viewModel::selectLevel,
            onSelectChain = viewModel::selectChain,
            onSelectMultisig = viewModel::selectMultisig,
            onSelectBitcoinProfile = viewModel::selectBitcoinProfile,
            onSelectSolanaProfile = viewModel::selectSolanaProfile,
            onSelectOutputMode = viewModel::selectOutputMode,
            onSelectTronIndex = viewModel::selectTronIndex,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectContent(uiState: ConnectUiState, toast: StatusToastState, actions: ConnectActions, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val title = when (uiState.level) {
        PairingLevel.Coin -> stringResource(R.string.pair_x, uiState.options.chain.displayName)
        PairingLevel.Wallet -> stringResource(R.string.pair_wallet)
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.page,
        topBar = {
            SignerTopAppBar(
                title = title,
                navigation = NavIcon.Back,
                onNavigate = actions.onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconAction(
                        icon = SignerIcons.Copy,
                        label = stringResource(R.string.copy_qr_data),
                        onClick = actions.onCopy,
                        enabled = uiState.copyText != null,
                        modifier = Modifier.testTag("connect.copy"),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                // As on the security pages: the keyboard (TRON index) adds only what it covers beyond the bar insets.
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(start = SignerMetrics.pagePadding, end = SignerMetrics.pagePadding, top = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.readableWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    LevelPicker(uiState.level, actions.onSelectLevel)
                    val reduceMotion = SignerTheme.reduceMotion
                    AnimatedContent(
                        targetState = uiState.level,
                        transitionSpec = { levelTransition(reduceMotion) },
                        label = "pairingLevel",
                    ) { level ->
                        when (level) {
                            PairingLevel.Coin -> CoinLevelPairing(uiState, actions)
                            PairingLevel.Wallet -> WalletLevelPairing(
                                payload = uiState.wallet,
                                matrices = uiState.walletMatrices,
                                error = uiState.error,
                                masterFingerprint = uiState.masterFingerprint,
                                onSubmitWallet = actions.onSubmitWallet,
                            )
                        }
                    }
                }
            }
            StatusToastHost(toast)
        }
    }
}

/** "Pairing level": "Wallet Level" then "Coin Level" (`ConnectView.swift:146-151`), tag `connect.level`. */
@Composable
private fun LevelPicker(level: PairingLevel, onSelect: (PairingLevel) -> Unit) {
    val levels = listOf(PairingLevel.Wallet, PairingLevel.Coin)
    SegmentedControl(
        options = listOf(stringResource(R.string.wallet_level), stringResource(R.string.coin_level)),
        selectedIndex = levels.indexOf(level),
        onSelect = { onSelect(levels[it]) },
        modifier = Modifier.fillMaxWidth().testTag("connect.level"),
        testTags = listOf("connect.level.wallet", "connect.level.coin"),
    )
}

/** `.snappy(0.25)` level switch: a quick cross-fade while the page height follows without bounce. */
private fun levelTransition(reduceMotion: Boolean): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(SignerMotion.disclosure(reduceMotion)),
    initialContentExit = fadeOut(SignerMotion.disclosure(reduceMotion)),
    sizeTransform = SizeTransform(clip = false) { _, _ -> SignerMotion.smooth(0.25, visibilityThreshold = IntSize(1, 1)) },
)

/** The QR area's content: what is shown and whether its arrival fades (`.smooth(0.2)`; the first Coin result does not). */
internal sealed interface QrSlot<out T> {
    val animate: Boolean

    /** [value] travels with the slot, so a slot that is fading out keeps drawing what it showed. */
    data class Loaded<T>(val value: T, override val animate: Boolean = true) : QrSlot<T>

    data class Failed(val text: UiText) : QrSlot<Nothing> {
        override val animate: Boolean get() = true
    }

    data object Loading : QrSlot<Nothing> {
        override val animate: Boolean get() = true
    }
}

/** The QR area transition: a 0.2 s fade, none when [QrSlot.animate] is false (it may land during the push). */
internal fun qrTransition(target: QrSlot<*>): ContentTransform =
    if (target.animate) {
        ContentTransform(fadeIn(SignerMotion.quick()), fadeOut(SignerMotion.quick()), sizeTransform = SizeTransform(clip = false))
    } else {
        ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = SizeTransform(clip = false))
    }
