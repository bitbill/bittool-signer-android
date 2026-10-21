package com.bittool.signer.feature.settings.reference

import android.os.Build
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.EmptyState
import com.bittool.signer.core.designsystem.component.SegmentedControl
import com.bittool.signer.core.designsystem.component.SignerDropdownMenu
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.model.registry.KnownContractEntry
import com.bittool.signer.core.model.registry.TokenRegistryEntry
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import com.bittool.signer.core.ui.logo.TokenIcon
import com.bittool.signer.core.ui.review.ContractCategoryGlyph
import com.bittool.signer.core.ui.wallet.isolate
import com.bittool.signer.feature.settings.SettingsPage
import com.bittool.signer.feature.settings.rememberResumed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of a network page (iOS `reference.picker` plus Android-only ids for the e2e suite). */
object ReferenceTags {
    const val PICKER = "reference.picker"
    const val TOKENS = "reference.tokens"
    const val CONTRACTS = "reference.contracts"
    const val SEARCH = "reference.search"
    const val LIST = "reference.list"
    const val TOKEN_ROW = "reference.token"
    const val CONTRACT_ROW = "reference.contract"
    /** Android-only: "Copy Contract Address" in a row's menu (iOS has no identifier for the context-menu item). */
    const val COPY = "reference.copy"
}

/** One network's tokens and contracts (inventory rows 67–68). */
@Composable
fun NetworkReferenceScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: NetworkReferenceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NetworkReferenceContent(
        state = state,
        query = viewModel.query,
        onBack = rememberResumed(onBack),
        onQueryChange = viewModel::onQueryChange,
        onSegmentSelected = viewModel::onSegmentSelected,
        modifier = modifier,
    )
}

/** Stateless network page. [query] is the field's text, read synchronously (see `NetworkReferenceViewModel.query`). */
@Composable
internal fun NetworkReferenceContent(
    state: NetworkReferenceUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSegmentSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    query: String = state.query,
) {
    val toast = rememberStatusToastState()
    val title = when (val t = state.title) {
        is ReferenceTitle.Network -> t.shortName
        ReferenceTitle.EveryEvmNetwork -> stringResource(R.string.all_evm_networks)
    }
    Box(modifier.fillMaxSize()) {
        SettingsPage(title = title, onBack = onBack) { insets ->
            Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding())) {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(if (state.showsContracts) R.string.name_project_or_address else R.string.symbol_name_or_address),
                    modifier = Modifier
                        .readableWidth()
                        .padding(horizontal = SignerMetrics.pagePadding, vertical = 6.dp)
                        .testTag(ReferenceTags.SEARCH),
                )
                if (state.showsPicker) {
                    SegmentedControl(
                        options = listOf(
                            stringResource(R.string.x_tokens, state.tokenCount),
                            stringResource(R.string.x_contracts, state.contractCount),
                        ),
                        selectedIndex = if (state.showsContracts) 1 else 0,
                        onSelect = onSegmentSelected,
                        modifier = Modifier
                            .readableWidth()
                            .padding(horizontal = SignerMetrics.pagePadding, vertical = 8.dp)
                            .testTag(ReferenceTags.PICKER),
                        testTags = listOf(ReferenceTags.TOKENS, ReferenceTags.CONTRACTS),
                    )
                }
                ReferenceLists(state, insets, toast, Modifier.weight(1f))
            }
        }
        StatusToastHost(toast)
    }
}

@Composable
private fun ReferenceLists(state: NetworkReferenceUiState, insets: PaddingValues, toast: StatusToastState, modifier: Modifier) {
    val reduceMotion = SignerTheme.reduceMotion
    val tokensState = rememberLazyListState()
    val contractsState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    // Scrolling the results puts the keyboard away, as a search list does on iOS.
    LaunchedEffect(tokensState.isScrollInProgress, contractsState.isScrollInProgress) {
        if (tokensState.isScrollInProgress || contractsState.isScrollInProgress) keyboard?.hide()
    }
    AnimatedContent(
        targetState = state.showsContracts,
        transitionSpec = {
            val millis = if (reduceMotion) 0 else SEGMENT_FADE_MILLIS
            fadeIn(tween(millis)) togetherWith fadeOut(tween(millis))
        },
        modifier = modifier.fillMaxWidth(),
        label = "referenceList",
    ) { contracts ->
        when {
            state.loading -> Unit
            // Decided per shown list, so the list fading out never flips to "No Results" during the cross-fade.
            (if (contracts) state.contracts.isEmpty() else state.tokens.isEmpty()) -> NoResults(state.query, insets)
            contracts -> ReferenceList(contractsState, insets) {
                items(state.contracts, key = { it.id }, contentType = { "contract" }) { ContractRow(it, toast) }
            }
            else -> ReferenceList(tokensState, insets) {
                items(state.tokens, key = { it.id }, contentType = { "token" }) { TokenRow(it, toast) }
            }
        }
    }
}

/** iOS `listStyle(.plain)`: full-width rows on the page, hairlines past the 36 dp icon. */
@Composable
private fun ReferenceList(
    state: LazyListState,
    insets: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag(ReferenceTags.LIST),
        contentPadding = PaddingValues(top = 4.dp, bottom = insets.calculateBottomPadding() + SignerMetrics.listBottomInset),
        content = content,
    )
}

/** iOS `ContentUnavailableView.search(text:)`: "No Results for “q”" and the hint. */
@Composable
private fun NoResults(query: String, insets: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = insets.calculateBottomPadding()),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = SignerIcons.Search,
            // The query keeps its own direction inside the sentence (an address in an Arabic sentence).
            title = stringResource(R.string.settings_no_results, isolate(query.trim())),
            description = stringResource(R.string.settings_no_results_hint),
        )
    }
}

/**
 * iOS `TokenRow` (`TokenDirectoryView.swift:307-329`): icon, symbol and name, the shortened contract address and
 * "%d dp". A long press offers "Copy Contract Address" (the full address); a tap does nothing, as on iOS.
 */
@Composable
private fun TokenRow(token: TokenRegistryEntry, toast: StatusToastState) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val decimals = stringResource(R.string.x_decimals, token.decimals)
    CopyMenuRow(address = token.assetAddress, toast = toast, tag = ReferenceTags.TOKEN_ROW) {
        TokenIcon(token.icon, token.symbol, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(token.symbol, style = typography.body.semibold(), color = colors.label, maxLines = 1)
                Text(
                    text = token.name,
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = AddressFormatter.short(token.assetAddress, 8, 6),
                style = typography.caption.monospaced().ltr(),
                color = colors.secondaryLabel,
            )
        }
        Text(
            text = stringResource(R.string.x_dp, token.decimals),
            style = typography.caption2,
            color = colors.tertiaryLabel,
            modifier = Modifier.semantics { contentDescription = decimals },
        )
    }
}

/**
 * iOS `ContractRow` (`TokenDirectoryView.swift:333-358`): the category glyph, the name (≤ 2 lines), "project ·
 * category" and the shortened address, read as one element; same long-press copy as tokens.
 */
@Composable
private fun ContractRow(contract: KnownContractEntry, toast: StatusToastState) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    CopyMenuRow(address = contract.address, toast = toast, tag = ReferenceTags.CONTRACT_ROW) {
        ContractCategoryGlyph(contract.categoryKind, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(contract.name, style = typography.body.semibold(), color = colors.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${contract.project} · ${contract.category}", style = typography.caption, color = colors.secondaryLabel)
            Text(
                text = AddressFormatter.short(contract.address, 8, 6),
                style = typography.caption.monospaced().ltr(),
                color = colors.secondaryLabel,
            )
        }
    }
}

/**
 * A plain-list row with the iOS context menu "Copy Contract Address". Holding the row shows the pressed ripple (the
 * Android counterpart of the iOS lift; not for a quick tap, which does nothing here, nor for a touch that turns into a
 * scroll), and the long press (with its haptic) opens the menu at the finger, as iOS shows it at the row (QA sign-15):
 * below and after the finger, or above / before it where the window has no room (near the keyboard, the bottom edge, the
 * far side). TalkBack gets the same as a long-click, the menu then under the row's start, and as a custom action. The
 * copy goes through [SignerClipboard] (expires after 120 s); Android 13+ confirms it itself, older versions get the
 * "Copied" toast.
 */
@Composable
private fun CopyMenuRow(
    address: String,
    toast: StatusToastState,
    tag: String,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val copyLabel = stringResource(R.string.copy_contract_address)
    val copied = stringResource(R.string.copied)
    var menu by remember { mutableStateOf(false) }
    // Where the long press happened, from the row's top left (null: TalkBack's long-click, which has no position).
    var pressedAt by remember { mutableStateOf<Offset?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val copy = {
        if (SignerClipboard.copy(context, address) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast.show(copied)
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .indication(interaction, ripple())
                .pointerInput(address) {
                    detectTapGestures(
                        onPress = { offset -> showPress(offset, interaction, scope) },
                        onLongPress = { offset ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            pressedAt = offset
                            menu = true
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    onLongClick(label = copyLabel) {
                        pressedAt = null
                        menu = true
                        true
                    }
                    customActions = listOf(CustomAccessibilityAction(copyLabel) { copy(); true })
                }
                .readableWidth()
                .padding(horizontal = SignerMetrics.pagePadding, vertical = 10.dp)
                .testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .readableWidth()
                .padding(start = SignerMetrics.pagePadding + 48.dp)
                .height(0.5.dp)
                .background(SignerTheme.colors.separator),
        )
        // A popup window: SignerDropdownMenu waits for the window gate like every other window (ARCHITECTURE §1.10)
        // and publishes the item's test tag as a resource id in debuggable builds (backlog B26, B29).
        if (menu) {
            val menuContent: @Composable () -> Unit = {
                SignerDropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(copyLabel) },
                        leadingIcon = { Icon(SignerIcons.Copy, contentDescription = null) },
                        onClick = {
                            menu = false
                            copy()
                        },
                        modifier = Modifier.testTag(ReferenceTags.COPY),
                    )
                }
            }
            val at = pressedAt
            if (at == null) {
                menuContent()
            } else {
                // The menu's anchor is a point under the finger, so DropdownMenu opens it with a corner at the finger
                // and flips it around that point near the window's edges. (An offset from the row would put a flipped
                // menu a row's height above the finger.) The press is measured from the left in either direction.
                Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { at.round() }) { menuContent() }
            }
        }
    }
}

/**
 * The pressed state of a row that answers only a long press (`clickable` does the same for its rows): drawn once the
 * finger has stayed [TAP_INDICATION_DELAY_MILLIS], so a quick tap or the start of a scroll draws nothing, and kept until
 * the finger lifts (released) or the gesture turns into a scroll (cancelled).
 */
private suspend fun PressGestureScope.showPress(offset: Offset, interaction: MutableInteractionSource, scope: CoroutineScope) {
    val press = PressInteraction.Press(offset)
    val shown = scope.launch {
        delay(TAP_INDICATION_DELAY_MILLIS)
        interaction.emit(press)
    }
    val released = tryAwaitRelease()
    if (shown.isActive) {
        shown.cancel()
    } else {
        interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
    }
}

/** Compose `clickable`'s delay before a press inside a scrolling list is drawn (the system tap timeout). */
private val TAP_INDICATION_DELAY_MILLIS: Long = ViewConfiguration.getTapTimeout().toLong()

private const val SEGMENT_FADE_MILLIS = 200
