package com.bittool.signer.feature.settings

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A page of the Settings area: the page colour behind the bars, [SignerTopAppBar] (large and collapsing for the tab
 * root, pinned otherwise — it lifts when content scrolls under it) and the safe-drawing insets handed to [content]
 * (whatever `:app`'s tab bar and the IME have not consumed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    PageScaffold(title, onBack, modifier, large, behavior, actions, content)
}

/** The tab root's page: a large title driven by the caller's [scrollBehavior] (see [scrollToTopAndExpand]). */
@ExperimentalMaterial3Api
@Composable
internal fun SettingsPage(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) = PageScaffold(title, onBack = null, modifier, large = true, scrollBehavior, actions, content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    large: Boolean,
    behavior: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            SignerTopAppBar(
                title = title,
                navigation = if (onBack != null) NavIcon.Back else NavIcon.None,
                onNavigate = { onBack?.invoke() },
                actions = actions,
                large = large,
                scrollBehavior = behavior,
            )
        },
        containerColor = SignerTheme.colors.page,
        contentColor = SignerTheme.colors.label,
        contentWindowInsets = WindowInsets.safeDrawing,
        content = content,
    )
}

/**
 * Content padding of a scrolling list under the top bar: side insets, [top] room and the bottom inset plus iOS
 * `listBottomInset()` (30). Apply the top inset to the list's frame (`Modifier.padding(top = …)`).
 */
@Composable
@ReadOnlyComposable
internal fun listContentPadding(insets: PaddingValues, top: Dp = 8.dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = insets.calculateStartPadding(direction),
        end = insets.calculateEndPadding(direction),
        top = top,
        bottom = insets.calculateBottomPadding() + SignerMetrics.listBottomInset,
    )
}

/**
 * Tab reselection (iOS: tapping the selected tab scrolls its root to the top and the large title comes back): the list
 * animates to its first item and the collapsed large bar expands with it. A programmatic scroll does not go through the
 * bar's nested-scroll connection, so without the second half the list would sit at the top under a collapsed title.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun scrollToTopAndExpand(list: LazyListState, bar: TopAppBarScrollBehavior) = coroutineScope {
    launch { list.animateScrollToItem(0) }
    val state = bar.state
    animate(state.heightOffset, 0f) { value, _ -> state.heightOffset = value }
    state.contentOffset = 0f
}

/** The horizontal frame of every section and floating callout: readable width, page gutter. */
internal val SectionFrame: Modifier = Modifier.readableWidth().padding(horizontal = SignerMetrics.pagePadding)

/** The gap between grouped sections (iOS inset-grouped section spacing). */
internal val SectionGap: Dp = 20.dp

/** Inner padding of a row that holds free content (text blocks, wallet grids) inside a `GroupedSection`. */
internal val RowContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/** A row with a small spinner while bundled data is decoded (instead of iOS's empty interpolations). */
@Composable
internal fun LoadingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = SignerTheme.colors.secondaryLabel, strokeWidth = 2.dp)
    }
}

/** A title over a detail line (Submit a Wallet "Include at least" items, labelled copy blocks): one TalkBack node. */
@Composable
internal fun TitledText(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(RowContentPadding)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = SignerTheme.typography.subheadline.semibold(), color = SignerTheme.colors.label)
        Text(detail, style = SignerTheme.typography.footnote, color = SignerTheme.colors.secondaryLabel)
    }
}

/**
 * Wraps a navigation callback so it only runs while the destination is resumed: a second tap during the push
 * transition (or on a page that is leaving) never navigates twice.
 */
@Composable
internal fun <T> rememberResumedAction(action: (T) -> Unit): (T) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(lifecycle, action) {
        { value: T -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action(value) }
    }
}

/** [rememberResumedAction] for a callback without argument. */
@Composable
internal fun rememberResumed(action: () -> Unit): () -> Unit {
    val wrapped = rememberResumedAction<Unit> { action() }
    return remember(wrapped) { { wrapped(Unit) } }
}
