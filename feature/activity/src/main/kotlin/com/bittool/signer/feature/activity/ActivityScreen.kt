package com.bittool.signer.feature.activity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.EmptyState
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.ui.text.rememberReviewText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Test tags of the Activity list (iOS accessibility identifiers, activity-settings.md §2.8). */
object ActivityTags {
    const val ROW = "activity.row"
    const val CLEAR = "activity.clear"
    const val LOCKED = "activity.locked"
    const val CLEAR_CONFIRM = "activity.clearConfirm"
    const val LIST = "activity.list"

    /** Android-only (QA L1): the notice that the record was started again, and its "OK". */
    const val RESTART_NOTICE = "activity.restartNotice"
    const val RESTART_NOTICE_OK = "activity.restartNotice.ok"
}

/** The Activity tab root (iOS `ActivityTab`, inventory row 56–57). */
@Composable
fun ActivityScreen(
    onOpenReceipt: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scrollToTop = remember(viewModel, lifecycle) { viewModel.scrollToTop.flowWithLifecycle(lifecycle) }
    ActivityContent(
        state = state,
        onOpenReceipt = rememberResumedAction(onOpenReceipt),
        onClear = viewModel::onClearClicked,
        onConfirmClear = viewModel::onClearConfirmed,
        onDismissClear = viewModel::onClearDismissed,
        modifier = modifier,
        scrollToTop = scrollToTop,
        onDismissRestartNotice = viewModel::onRestartNoticeDismissed,
    )
}

/**
 * Stateless Activity list (previews, screenshots and Compose tests). Each [scrollToTop] emission (tab reselection)
 * scrolls the list back to the top and expands the large title.
 *
 * Android-only (QA L1): after the record was started again because its key was lost with the screen lock, the tab says
 * so once ([ActivityUiState.restartNotice]): as the empty state while nothing was signed since, else above the list,
 * until "OK" ([onDismissRestartNotice]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityContent(
    state: ActivityUiState,
    onOpenReceipt: (String) -> Unit,
    onClear: () -> Unit,
    onConfirmClear: () -> Unit,
    onDismissClear: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    scrollToTop: Flow<Unit> = emptyFlow(),
    onDismissRestartNotice: () -> Unit = {},
) {
    val toast = rememberStatusToastState()
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(scrollToTop, listState, bar) { scrollToTop.collect { scrollToTopAndExpand(listState, bar) } }
    Box(modifier.fillMaxSize()) {
        ActivityPage(
            title = stringResource(R.string.activity),
            scrollBehavior = bar,
            actions = {
                if (state.canClear) {
                    TextAction(
                        text = stringResource(R.string.clear),
                        onClick = onClear,
                        destructive = true,
                        modifier = Modifier.testTag(ActivityTags.CLEAR),
                    )
                }
            },
        ) { insets ->
            when {
                state.loading -> Unit
                state.locked -> CenteredState(insets) {
                    EmptyState(
                        icon = SignerIcons.DeviceLock,
                        title = stringResource(R.string.wallets_keys_locked_title),
                        description = stringResource(R.string.crypto_device_locked),
                        modifier = Modifier.testTag(ActivityTags.LOCKED),
                    )
                }
                state.unreadable -> CenteredState(insets) {
                    EmptyState(
                        icon = SignerIcons.StorageAlert,
                        title = stringResource(R.string.activity_unreadable_title),
                        description = stringResource(R.string.data_activity_unreadable),
                    )
                }
                state.days.isEmpty() && state.restartNotice -> CenteredState(insets) {
                    EmptyState(
                        icon = SignerIcons.History,
                        title = stringResource(R.string.activity_restarted_title),
                        description = stringResource(R.string.activity_restarted_message),
                        modifier = Modifier.testTag(ActivityTags.RESTART_NOTICE),
                        action = {
                            SecondaryButton(
                                text = stringResource(R.string.ok),
                                onClick = onDismissRestartNotice,
                                fillWidth = false,
                                modifier = Modifier.testTag(ActivityTags.RESTART_NOTICE_OK),
                            )
                        },
                    )
                }
                state.days.isEmpty() -> CenteredState(insets) {
                    EmptyState(
                        icon = SignerIcons.Signature,
                        title = stringResource(R.string.no_signatures_yet),
                        description = stringResource(R.string.signatures_you_create_on_this_device_are_listed_here),
                    )
                }
                else -> ActivityList(state, insets, listState, onOpenReceipt, onDismissRestartNotice)
            }
        }
        StatusToastHost(toast)
    }
    if (state.showsClearConfirmation) ClearActivityDialog(toast, onConfirmClear, onDismissClear)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivityList(
    state: ActivityUiState,
    insets: PaddingValues,
    listState: LazyListState,
    onOpenReceipt: (String) -> Unit,
    onDismissRestartNotice: () -> Unit,
) {
    val formats = rememberActivityDateFormats()
    val reviewText = rememberReviewText()
    val resources = LocalResources.current
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .testTag(ActivityTags.LIST),
        contentPadding = listContentPadding(insets, top = 0.dp),
    ) {
        if (state.restartNotice) {
            item(key = "restartNotice", contentType = "notice") {
                RestartNotice(onDismissRestartNotice, SectionFrame.padding(top = 8.dp, bottom = 4.dp))
            }
        }
        state.days.forEach { day ->
            stickyHeader(key = "header-${day.key}", contentType = "header") {
                DayHeader(formats.day(day.date))
            }
            item(key = "day-${day.key}", contentType = "day") {
                GroupedSection(SectionFrame) {
                    day.entries.forEach { entry ->
                        item(key = entry.id, dividerInset = ROW_DIVIDER_INSET) {
                            ActivityRow(
                                entry = entry,
                                title = receiptTitle(entry.title, reviewText, resources),
                                time = formats.time(entry.dateMillis),
                                onClick = { onOpenReceipt(entry.id) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "footer", contentType = "footer") {
            ListFootnote(
                text = stringResource(R.string.a_local_record_of_signatures_created_on_this_device),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * iOS `ActivityRow` (`ActivityTab.swift:63-88`): logo, title (≤ 2 lines), "summary · wallet" and the time. At
 * accessibility text sizes the time moves under the subtitle, so the title keeps the row's width instead of being cut
 * after a word or two beside a large time.
 */
@Composable
private fun ActivityRow(entry: ActivityEntry, title: String, time: String, onClick: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val wallet = entry.wallet?.text()
    val subtitle = listOfNotNull(entry.summary.ifEmpty { null }, wallet).joinToString(" · ")
    val stacked = SignerTheme.isAccessibilityTextSize
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(ActivityTags.ROW),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReceiptLogo(entry.chain, entry.chainId, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = typography.subheadline.semibold().contentDirection(),
                color = colors.label,
                maxLines = if (stacked) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = typography.caption.contentDirection(),
                    color = colors.secondaryLabel,
                    maxLines = if (stacked) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stacked) Text(time, style = typography.caption.monospacedDigit(), color = colors.secondaryLabel, maxLines = 1)
        }
        if (!stacked) Text(time, style = typography.caption.monospacedDigit(), color = colors.secondaryLabel, maxLines = 1)
        Icon(SignerIcons.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(18.dp))
    }
}

/** QA L1: the record was started again (its key went with the screen lock); "OK" ends the notice for good. */
@Composable
private fun RestartNotice(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag(ActivityTags.RESTART_NOTICE), horizontalAlignment = Alignment.End) {
        Callout(
            text = stringResource(R.string.activity_restarted_message),
            title = stringResource(R.string.activity_restarted_title),
            tone = Tone.Info,
        )
        TextAction(
            text = stringResource(R.string.ok),
            onClick = onDismiss,
            emphasized = true,
            modifier = Modifier.testTag(ActivityTags.RESTART_NOTICE_OK),
        )
    }
}

/** An empty or failure state centred in the space under the bar, scrollable at large text sizes. */
@Composable
private fun CenteredState(insets: PaddingValues, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

/** "Clear all activity?" (iOS `confirmationDialog`, `ActivityTab.swift:53-57`). */
@Composable
private fun ClearActivityDialog(toast: StatusToastState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val overlayBlocked = stringResource(R.string.security_overlay_blocked)
    SignerAlertDialog(
        title = stringResource(R.string.clear_all_activity),
        text = stringResource(R.string.this_only_removes_the_local_records_it_does_not_affect),
        confirm = DialogAction(
            text = stringResource(R.string.clear_activity),
            destructive = true,
            testTag = ActivityTags.CLEAR_CONFIRM,
            onObscuredTouch = { toast.show(overlayBlocked, SignerIcons.WarningFilled, HapticKind.Warning) },
            onClick = onConfirm,
        ),
        dismiss = DialogAction(text = stringResource(R.string.cancel), onClick = onDismiss),
        onDismissRequest = onDismiss,
    )
}

/** Hairline inset under a row: past the 36 dp logo (16 + 36 + 12). */
private val ROW_DIVIDER_INSET = 64.dp
