package com.bittool.signer.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.FirstLineIcon
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.SymbolHero
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.navigation.SetupIntent
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * The three-page introduction shown before the first wallet is created or imported (iOS `OnboardingView`,
 * Features/Onboarding/WelcomeView.swift:116-245; app-shell.md §6). "Safety guidance is a required step; there is
 * intentionally no "Skip"."
 *
 * - Horizontal swipe between pages; the bottom bar holds the page indicator (one node, "Page n of 3") and the primary
 *   button: "Continue" on pages 1–2, "Get Started" on page 3, which calls [onComplete] (`:app` records
 *   `onboardingVersionCompleted` and opens the setup steps for the same [intent]).
 * - The top bar has "Cancel" (start: [onCancel], the setup overlay closes and the introduction shows again next time)
 *   and "Back" (end, only after the first page).
 * - System Back (and the predictive gesture) goes to the previous page, and from the first page cancels.
 * - [intent] is the Create / Import choice the introduction leads into; it does not change the pages (iOS keeps it
 *   for the same reason: the steps after it depend on it, not the introduction).
 *
 * Stateless apart from the current page, which survives configuration changes (not a secret).
 */
@Composable
fun OnboardingPager(
    intent: SetupIntent,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { OnboardingPages.size }
    // One per page, hoisted so the bar knows when the shown page continues under it (large text, short windows).
    val scrollStates = List(OnboardingPages.size) { rememberScrollState() }
    val scope = rememberCoroutineScope()
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnComplete by rememberUpdatedState(onComplete)
    val lastPage = OnboardingPages.lastIndex
    val previous: () -> Unit = {
        val target = pagerState.targetPage
        if (target > 0) scope.launch { pagerState.animateScrollToPage(target - 1) } else currentOnCancel()
    }
    BackHandler(onBack = previous)

    Column(
        modifier
            .fillMaxSize()
            .background(SignerTheme.colors.page)
            .testTag(OnboardingTags.ROOT),
    ) {
        OnboardingTopBar(showsBack = pagerState.currentPage > 0, onCancel = { currentOnCancel() }, onBack = previous)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { it },
        ) { index ->
            OnboardingPageView(OnboardingPages[index], scrollStates[index])
        }
        BottomActionBar(
            maxWidth = SignerMetrics.narrowWidth,
            canScrollUnder = scrollStates[pagerState.currentPage].canScrollForward,
        ) {
            PageIndicator(
                pagerState = pagerState,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp),
            )
            val onLast = pagerState.currentPage == lastPage
            PrimaryButton(
                text = stringResource(if (onLast) R.string.get_started else R.string.continue_text),
                onClick = {
                    val target = pagerState.targetPage
                    if (target < lastPage) scope.launch { pagerState.animateScrollToPage(target + 1) } else currentOnComplete()
                },
                modifier = Modifier.testTag(OnboardingTags.NEXT),
            )
        }
    }
}

/** The inline navigation bar: "Cancel" on the start side, "Back" on the end side after the first page. */
@Composable
private fun OnboardingTopBar(showsBack: Boolean, onCancel: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextAction(text = stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.testTag(OnboardingTags.CANCEL))
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = showsBack, enter = fadeIn(), exit = fadeOut()) {
            TextAction(text = stringResource(R.string.back), onClick = onBack, modifier = Modifier.testTag(OnboardingTags.BACK))
        }
    }
}

/**
 * One page (WelcomeView.swift:203-244): the hero, the title and body, and the key points on a card. Short windows
 * (under 560 dp, small phones and landscape) get a smaller hero so the points stay above the bottom bar. Scrolls when
 * the text is larger than the window (font scale 2).
 */
@Composable
private fun OnboardingPageView(page: OnboardingPage, scrollState: ScrollState) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < CompactHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .readableWidth(SignerMetrics.narrowWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp),
        ) {
            SymbolHero(
                icon = page.hero,
                size = if (compact) 36.dp else 56.dp,
                bounce = true,
                modifier = Modifier.padding(top = if (compact) 4.dp else 16.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(page.title),
                    style = typography.largeTitle.bold(),
                    color = colors.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(page.body),
                    style = typography.body,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
            SignerCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    page.points.forEach { point ->
                        // The symbol is the point's identity (iOS `Point.id`), not its translated text.
                        key(point.id) {
                            Row(verticalAlignment = Alignment.Top) {
                                // Centred on the first line, like the iOS `Label` icon, also with large text.
                                FirstLineIcon(
                                    icon = point.icon,
                                    tint = colors.accent,
                                    style = typography.subheadline,
                                    size = 20.dp,
                                    modifier = Modifier.width(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(point.text),
                                    style = typography.subheadline,
                                    color = colors.label,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The page dots (WelcomeView.swift:172-181): the current page is a 22 × 8 dp accent capsule, the others 8 × 8 dp in the
 * system fill, 8 dp apart. Width and colour follow the swipe continuously (iOS animates them with `.snappy`); drawn
 * without recomposition while the pager moves. One accessibility node: "Page n of 3". Mirrored in right-to-left
 * layouts like the pager.
 */
@Composable
private fun PageIndicator(pagerState: PagerState, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val count = pagerState.pageCount
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val description = stringResource(R.string.page_x_of_x, pagerState.currentPage + 1, count)
    val width: Dp = DotActiveWidth + (DotSize + DotGap) * (count - 1)
    Canvas(
        modifier
            .size(width, DotSize)
            // One node: the description and the tag (a tag outside the clearing modifier would be cleared with the rest).
            .clearAndSetSemantics {
                contentDescription = description
                testTag = OnboardingTags.INDICATOR
            },
    ) {
        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
        val height = size.height
        val gap = DotGap.toPx()
        var x = 0f
        for (index in 0 until count) {
            val distance = abs(position - index).coerceAtMost(1f)
            val dotWidth = DotActiveWidth.toPx() + (DotSize.toPx() - DotActiveWidth.toPx()) * distance
            val left = if (rtl) size.width - x - dotWidth else x
            drawRoundRect(
                color = lerp(colors.accent, colors.fill, distance),
                topLeft = Offset(left, 0f),
                size = Size(dotWidth, height),
                cornerRadius = CornerRadius(height / 2f),
            )
            x += dotWidth + gap
        }
    }
}

/** Test tags of the introduction (the iOS identifier `onboarding.next`, plus Android-only ones). */
object OnboardingTags {
    const val ROOT = "onboarding"
    const val NEXT = "onboarding.next"
    const val CANCEL = "onboarding.cancel"
    const val BACK = "onboarding.back"
    const val INDICATOR = "onboarding.pages"
}

private val CompactHeight = 560.dp
private val DotSize = 8.dp
private val DotActiveWidth = 22.dp
private val DotGap = 8.dp
