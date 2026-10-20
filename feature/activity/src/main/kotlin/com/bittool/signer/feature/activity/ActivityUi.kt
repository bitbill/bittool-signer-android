package com.bittool.signer.feature.activity

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
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
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.ui.logo.GenericNetworkLogo
import com.bittool.signer.core.ui.logo.NetworkLogo
import com.bittool.signer.core.ui.text.currentLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A page of the Activity tab: the page colour behind the bars, [SignerTopAppBar] (large for the tab root, collapsing
 * with the content) and the safe-drawing insets handed to [content] (the tab bar and IME insets `:app` has not consumed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    PageScaffold(title, onBack, modifier, large, behavior, actions, content)
}

/** The tab root's page: a large title driven by the caller's [scrollBehavior] (see [scrollToTopAndExpand]). */
@ExperimentalMaterial3Api
@Composable
internal fun ActivityPage(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
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
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
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
 * Content padding of a scrolling list below the top bar: the side insets, [top] extra room, and the bottom inset plus
 * iOS `listBottomInset()` (30). The top inset itself is applied to the list's frame so sticky headers stop under the
 * bar.
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

/** The horizontal frame of every section: readable width, page gutter. */
internal val SectionFrame: Modifier = Modifier.readableWidth().padding(horizontal = SignerMetrics.pagePadding)

/**
 * A footnote under a list that has no rows of its own (iOS an empty `Section` with a footer): the
 * `GroupedSection` footer style, aligned with the section text.
 */
@Composable
internal fun ListFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SignerTheme.typography.footnote,
        color = SignerTheme.colors.secondaryLabel,
        modifier = modifier.then(SectionFrame).padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/** A sticky day header on the opaque page colour (rows never show through), styled like a `GroupedSection` header. */
@Composable
internal fun DayHeader(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(SignerTheme.colors.page)) {
        Text(
            text = text,
            style = SignerTheme.typography.subheadline.semibold(),
            color = SignerTheme.colors.accentOnTint,
            modifier = SectionFrame
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                .semantics { heading() },
        )
    }
}

/** iOS `ReceiptLogo`: the receipt's network logo, or the generic badge for an EVM chain without bundled artwork. */
@Composable
internal fun ReceiptLogo(chain: Chain, chainId: String?, size: Dp, modifier: Modifier = Modifier) {
    val brand = receiptBrand(chain, chainId)
    if (brand != null) NetworkLogo(brand, modifier, size) else GenericNetworkLogo(modifier, size)
}

/**
 * A receipt title (engine English) in the interface language where the catalogue has the phrase, else the English
 * (activity-settings.md §12.2); the engine's default title is the translated "Review request".
 */
internal fun receiptTitle(title: String, reviewText: ReviewText, resources: android.content.res.Resources): String =
    if (title == TransactionReview.DEFAULT_TITLE) resources.getString(R.string.ui_review_request) else reviewText.headline(title)

/**
 * The status pill text (iOS shows the stored `resultType` verbatim, `ActivityTab.swift:129-130`): the two values the
 * store writes are translated, anything else stays verbatim, and a record without one says "Signature created".
 */
@Composable
@ReadOnlyComposable
internal fun resultText(resultType: String?): String = when (resultType) {
    null, SigningReceipt.RESULT_SIGNATURE -> stringResource(R.string.signature_created_2)
    SigningReceipt.RESULT_PARTIAL -> stringResource(R.string.activity_partial_signature_created)
    else -> resultType
}

/** The wallet as the row and the detail name it. */
@Composable
@ReadOnlyComposable
internal fun WalletLabel.text(): String = when (this) {
    is WalletLabel.Named -> name
    WalletLabel.Deleted -> stringResource(R.string.activity_deleted_wallet)
}

/**
 * Formats Activity dates in the app locale (activity-settings.md §14.2); one instance per composition locale. Every
 * format writes the locale's own digits (`java.time` would otherwise always write ASCII digits, so with Arabic as the
 * device language the day header and the row time would disagree; the app's own Arabic is `ar-u-nu-latn`), and both
 * times follow the device's 24-hour setting, like iOS.
 */
internal class ActivityDateFormats(context: Context, locale: Locale) {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val digits: DecimalStyle = DecimalStyle.of(locale)

    /** Section header, iOS `.dateTime.weekday(.wide).month().day().year()` ("Tuesday, Oct 20, 2026"). */
    private val day: DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMdy"), locale).withDecimalStyle(digits)

    /** Row time, iOS `.dateTime.hour().minute()`, honouring the 24-hour setting. */
    private val time: java.text.DateFormat = DateFormat.getTimeFormat(context)

    /**
     * Detail "Date", iOS `.formatted(date: .abbreviated, time: .standard)` ("Oct 20, 2026, 5:42:00 PM", or 17:42:00 with
     * the 24-hour setting), in the app locale.
     */
    private val dateTime: DateTimeFormatter = DateTimeFormatter
        .ofPattern(DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "yMMMdHms" else "yMMMdhms"), locale)
        .withDecimalStyle(digits)

    fun day(date: LocalDate): String = day.format(date)
    fun time(millis: Long): String = time.format(Date(millis))
    fun dateTime(millis: Long): String = dateTime.format(Instant.ofEpochMilli(millis).atZone(zone))
}

@Composable
internal fun rememberActivityDateFormats(): ActivityDateFormats {
    val context = LocalContext.current
    val locale = currentLocale()
    val resources = LocalResources.current
    return remember(context, locale, resources) { ActivityDateFormats(context, locale) }
}

/**
 * Wraps a navigation callback so it only runs while the destination is resumed: a second tap during the push
 * transition (or a tap on a page that is leaving) never navigates twice.
 */
@Composable
internal fun <T> rememberResumedAction(action: (T) -> Unit): (T) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(lifecycle, action) {
        { value: T -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action(value) }
    }
}
