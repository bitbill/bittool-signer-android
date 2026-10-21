package com.bittool.signer.feature.setup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView as bringThisNodeIntoView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.FirstLineIcon
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.NavIconTags
import com.bittool.signer.core.designsystem.component.PhraseText
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.icon
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.component.toneColor
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.keepInViewAboveBar
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.headingLineBreaks
import com.bittool.signer.core.designsystem.theme.phraseLineBreaks
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.ui.text.Callout
import com.bittool.signer.core.ui.text.asString
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.bittool.signer.core.l10n.R as L10n

// ---------------------------------------------------------------------------------------------------------------------
// Composition locals of the feature

/** The recovery-session factory of the hosting screen (setup flow or Recovery Phrase page). */
internal val LocalRecoverySessions = staticCompositionLocalOf<RecoverySessionFactory> {
    error("LocalRecoverySessions is not provided")
}

/**
 * Called when a decisive control refused a touch that came through another app's overlay (ARCHITECTURE §4.8). The
 * decisive controls (`setup.finish`, `recovery.reveal`, `cards.reveal`, `backup.qr.reveal`, `vaultManagement.submit`)
 * pass it to `Modifier.obscuredTouchGuard`.
 */
internal val LocalOverlayBlocked = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Hosts [content] with a toast that reports refused overlay touches (`security_overlay_blocked`, warning haptic), in the
 * current window. Sheets are separate windows: they wrap their content in their own host.
 */
@Composable
internal fun OverlayGuardHost(modifier: Modifier = Modifier, fill: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val toast = rememberStatusToastState()
    val message = stringResource(L10n.string.security_overlay_blocked)
    val onBlocked = remember(toast, message) { { toast.show(message, SignerIcons.WarningFilled, HapticKind.Warning) } }
    Box(if (fill) modifier.fillMaxSize() else modifier) {
        CompositionLocalProvider(LocalOverlayBlocked provides onBlocked) { content() }
        StatusToastHost(toast, Modifier.matchParentSize())
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Top bar

/** Leading affordance of a setup page (setup-backup §15.5). */
internal enum class SetupNavigation {
    /** Done step: no way back from the bar. */
    None,

    /** First step: a cross labelled "Cancel" (iOS leading "Cancel"). */
    Cancel,

    /** Pushed steps: the mirrored back arrow, tagged `nav.back` for the e2e suite (gaps.md G1.0). */
    Back,
}

/**
 * The page bar of the setup steps and the Recovery Phrase page: `SignerTopAppBar` (page colour, lifted when content
 * scrolls under it, inline `headline` title) with the setup navigation: the mirrored back arrow (`nav.back`) or the
 * first step's cross, read "Cancel" and tagged `setup.cancel`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupTopBar(
    title: String,
    navigation: SetupNavigation,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    navigationLabel: String = stringResource(if (navigation == SetupNavigation.Back) L10n.string.back else L10n.string.cancel),
    navigationTag: String = if (navigation == SetupNavigation.Back) NAV_BACK_TAG else CANCEL_TAG,
    navigationEnabled: Boolean = true,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    SignerTopAppBar(
        title = title,
        navigation = when (navigation) {
            SetupNavigation.None -> NavIcon.None
            SetupNavigation.Cancel -> NavIcon.Close
            SetupNavigation.Back -> NavIcon.Back
        },
        onNavigate = onNavigate,
        modifier = modifier,
        actions = actions,
        scrollBehavior = scrollBehavior,
        navigationTag = navigationTag,
        navigationLabel = navigationLabel,
        navigationEnabled = navigationEnabled,
    )
}

/** The trailing "Cancel" of pushed steps (iOS `.topBarTrailing`). */
@Composable
internal fun CancelAction(enabled: Boolean, onClick: () -> Unit) {
    TextAction(stringResource(L10n.string.cancel), onClick = onClick, enabled = enabled, modifier = Modifier.testTag(CANCEL_TAG))
}

internal const val NAV_BACK_TAG = NavIconTags.BACK
internal const val CANCEL_TAG = "setup.cancel"

// ---------------------------------------------------------------------------------------------------------------------
// Page scaffold

/**
 * Centres registered elements of a scrolling page (iOS `ScrollViewReader.scrollTo(id, anchor: .center)` with
 * `.snappy`): the backup check keeps its active blank in the middle of the screen.
 */
@Stable
internal class ScrollCentering {
    internal var content: LayoutCoordinates? = null
    internal var viewportHeight: Int = 0
    private val targets = HashMap<Any, LayoutCoordinates>()

    fun register(key: Any, coordinates: LayoutCoordinates) {
        targets[key] = coordinates
    }

    suspend fun center(key: Any, scroll: ScrollState) {
        val content = content?.takeIf { it.isAttached } ?: return
        val target = targets[key]?.takeIf { it.isAttached } ?: return
        val middle = content.localPositionOf(target, Offset.Zero).y + target.size.height / 2f
        val offset = (middle - viewportHeight / 2f).roundToInt().coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(offset, SignerMotion.snappy(0.35))
    }

    /** [center]s [key] only when it is not entirely inside the viewport (nothing moves when it is already visible). */
    suspend fun centerIfHidden(key: Any, scroll: ScrollState) {
        val content = content?.takeIf { it.isAttached } ?: return
        val target = targets[key]?.takeIf { it.isAttached } ?: return
        val top = content.localPositionOf(target, Offset.Zero).y
        val bottom = top + target.size.height
        if (top >= scroll.value && bottom <= scroll.value + viewportHeight) return
        center(key, scroll)
    }
}

/**
 * Keeps this block in view as a whole when something inside it asks to be brought into view — the field that gains
 * focus, the line with the caret of the field being typed in — and the block fits the visible part of the page;
 * otherwise only what was asked (QA setup-02; iOS scrolls the focused field above the keyboard toolbar). Put it on what
 * belongs together while one of its fields is typed in: the phrase editor with its status line, both passphrase
 * fields, the wallet password pair with its requirements checklist.
 *
 * Each request from inside is passed on as two at once, the whole block and what was asked. The scroll container
 * shows the larger one that fits (Compose's bring-into-view queue), and [SetupScaffold]'s `keepInViewAboveBar` keeps
 * both clear of the bottom bar and makes them again whenever the bar grows with the keyboard, so the block (or, when it
 * no longer fits the shrunken viewport, the caret line) moves back above the bar.
 */
internal fun Modifier.keepTogetherInView(): Modifier = this then KeepTogetherInViewElement

private data object KeepTogetherInViewElement : ModifierNodeElement<KeepTogetherInViewNode>() {
    override fun create(): KeepTogetherInViewNode = KeepTogetherInViewNode()
    override fun update(node: KeepTogetherInViewNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "keepTogetherInView"
    }
}

private class KeepTogetherInViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override val shouldAutoInvalidate: Boolean get() = false

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        if (!isAttached) return
        coroutineScope {
            // The whole block first, then what was asked: `keepInViewAboveBar` remembers the block as the latest request
            // for a whole element and the asked part as the latest request, and makes both again when the bar grows.
            launch(start = CoroutineStart.UNDISPATCHED) { bringThisNodeIntoView() }
            bringThisNodeIntoView {
                if (!isAttached || !childCoordinates.isAttached) return@bringThisNodeIntoView null
                val asked = boundsProvider() ?: return@bringThisNodeIntoView null
                Rect(requireLayoutCoordinates().localPositionOf(childCoordinates, asked.topLeft), asked.size)
            }
        }
    }
}

/**
 * The shared layout of setup steps 1–4 (iOS `SetupScaffold`, setup-backup §5): a scrolling column 20 dp in, at most
 * 640 dp wide, starting with the step header (segmented progress, "Step n of total", large title, message — one
 * accessibility element), then [content] 20 dp apart; [footer] sits in the bottom action bar above the navigation bar
 * and the keyboard. [footerBottomPadding] 0 lets an IME-attached row sit flush on the keyboard.
 *
 * The bar rises with the keyboard and the scrolling viewport above it shrinks: `keepInViewAboveBar` keeps the focused
 * field, and the line being typed, above the bar and its edge fade, also while the bar grows (QA setup-02). Blocks that
 * should stay visible together while one of their fields is typed in carry [keepTogetherInView].
 *
 * The in-app recovery-phrase keyboard ([keyboard], shown while [keyboardVisible]) is part of the bottom bar, under the
 * footer and full width: it slides up from the bottom edge like a system keyboard, the bar grows with it, and the same
 * `keepInViewAboveBar` keeps the editor above both. While it is up the footer leaves the navigation-bar inset to it
 * (the keyboard's tray runs under the navigation bar), continuously while it slides. [footerVisible] false hides the
 * footer (a window too short for the footer, the keyboard and the editor together).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupScaffold(
    topBar: @Composable (TopAppBarScrollBehavior) -> Unit,
    step: Int?,
    total: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    centering: ScrollCentering? = null,
    footerBottomPadding: Dp = 8.dp,
    footerMaxWidth: Dp = SignerMetrics.readableWidth,
    footerVisible: Boolean = true,
    keyboardVisible: Boolean = false,
    keyboard: @Composable () -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val density = LocalDensity.current
    val reduceMotion = SignerTheme.reduceMotion
    // The keyboard's current (animated) height: the footer's bottom inset is reduced by it, so the footer never jumps.
    var keyboardHeight by remember { mutableStateOf(0.dp) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { topBar(scrollBehavior) },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                // Not in an AnimatedVisibility: its clip would cut off the fade the bar draws above itself. The
                // keyboard's slide covers the change.
                if (footerVisible) {
                    BottomActionBar(
                        modifier = Modifier.consumeWindowInsets(PaddingValues(bottom = keyboardHeight)),
                        canScrollUnder = scrollState.canScrollForward,
                        bottomPadding = footerBottomPadding,
                        maxWidth = footerMaxWidth,
                        content = footer,
                    )
                }
                KeyboardSlot(
                    visible = keyboardVisible,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.onSizeChanged { keyboardHeight = with(density) { it.height.toDp() } },
                    keyboard = keyboard,
                )
            }
        },
        containerColor = SignerTheme.colors.page,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { centering?.viewportHeight = it.height }
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                // The padding sits outside the scroll: the viewport ends at the bar's top edge and shrinks as it grows.
                .keepInViewAboveBar(padding.calculateBottomPadding(), scrollsUnderBar = false),
        ) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .readableWidth()
                    .onGloballyPositioned { centering?.content = it }
                    .padding(SignerMetrics.pagePadding),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (step != null) StepHeader(step, total, title, message)
                content()
            }
        }
    }
}

/**
 * Where [SetupScaffold] puts the in-app keyboard: full width under the footer, anchored at the bottom edge so it slides
 * up into the growing bar (none without animations). The slot stays laid out (0 high) while the keyboard is gone, so
 * [modifier]'s size reports always end at 0.
 */
@Composable
private fun KeyboardSlot(visible: Boolean, reduceMotion: Boolean, modifier: Modifier, keyboard: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = visible,
            enter = if (reduceMotion) EnterTransition.None else expandVertically(KeyboardMotion, expandFrom = Alignment.Bottom),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(KeyboardMotion, shrinkTowards = Alignment.Bottom),
        ) { keyboard() }
    }
}

/** The in-app keyboard's slide (about the system keyboard's 0.3 s, no bounce). */
private val KeyboardMotion: FiniteAnimationSpec<IntSize> = SignerMotion.smooth(0.3, IntSize.VisibilityThreshold)

/** The step header: progress, "Step %lld of %lld", title and message as one element (setup-backup §5, §12.1). */
@Composable
private fun StepHeader(step: Int, total: Int, title: String, message: String) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StepProgress(step, total, Modifier.padding(bottom = 6.dp))
        Text(stringResource(L10n.string.step_x_of_x, step, total), style = typography.footnote.semibold(), color = colors.accent)
        LargeTitle(title)
        BodyText(message, style = typography.body, color = colors.secondaryLabel)
    }
}

/** The Android polish of setup-backup §5: one capsule per step, the reached ones in the accent (decorative). */
@Composable
private fun StepProgress(step: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Row(modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { index ->
            val color by animateColorAsState(if (index < step) colors.accent else colors.fill, SignerMotion.quick(), label = "stepProgress")
            Box(Modifier.weight(1f).height(4.dp).background(color, CircleShape))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Small building blocks

/**
 * A setup large title (iOS `.font(.largeTitle.weight(.bold))`), a heading for TalkBack. Balanced lines that break
 * between phrases in Japanese and Korean, also where the platform cannot (Japanese below API 33, Korean below API 35;
 * QA setup-15, regression-13): the design system's [PhraseText] in a `headingLineBreaks` style, which TalkBack and the
 * accessibility tree read exactly as written.
 */
@Composable
internal fun LargeTitle(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    PhraseText(
        text = text,
        style = SignerTheme.typography.largeTitle.headingLineBreaks(),
        color = SignerTheme.colors.label,
        textAlign = textAlign,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * Setup's running text (a step's message, a note under a control, a tip): [PhraseText] in [style] with
 * `phraseLineBreaks`, so Japanese and Korean lines break between phrases on every Android version.
 */
@Composable
internal fun BodyText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    PhraseText(text = text, style = style.phraseLineBreaks(), color = color, textAlign = textAlign, modifier = modifier)
}

/**
 * A footer error (iOS `Callout(verbatim: error, tone: .danger)`): the shared `UiText` callout, so engine text keeps its
 * own direction. Tagged `setup.error`.
 *
 * With a [maxLines] (the import step while the in-app keyboard is up, at accessibility text sizes or on a short window:
 * QA activity-keyboard-03) the message is drawn on at most that many lines, ending in an ellipsis, so the bar leaves
 * the page room for what is being fixed; TalkBack still reads the whole message, and without the limit it is drawn
 * whole again. It is the same callout (tone at 12 %, radius 16, padding 14, the first-line glyph, subheadline in the
 * message's own direction), drawn here because the design system's has no line limit; its text is tagged
 * `setup.error.text`.
 */
@Composable
internal fun ErrorCallout(error: UiText?, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    if (error == null) return
    if (maxLines == Int.MAX_VALUE) {
        Callout(text = error, modifier = modifier.testTag(ERROR_TAG), tone = Tone.Danger)
        return
    }
    val colors = SignerTheme.colors
    val style = SignerTheme.typography.subheadline.contentDirection()
    val color = colors.toneColor(Tone.Danger)
    Row(
        modifier = modifier
            .testTag(ERROR_TAG)
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), SignerShapes.callout)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FirstLineIcon(Tone.Danger.icon(), color, style)
        val text = error.asString()
        PhraseText(
            text = text,
            style = style.phraseLineBreaks(),
            color = colors.label,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(ERROR_TEXT_TAG),
        )
    }
}

/** How many lines a footer error keeps while the in-app keyboard is up and space is short ([ErrorCallout]). */
internal const val SHORTENED_ERROR_LINES = 2

private const val ERROR_TAG = "setup.error"
private const val ERROR_TEXT_TAG = "setup.error.text"

/**
 * An inline tinted text button with an optional leading icon and trailing direction arrow (iOS `Button { Label(…) }`
 * in subheadline semibold: "Hide Recovery Phrase", "Hide QR Code", "Recovery Cards →", "Show the Phrase Again"). At
 * least 48 dp high. Directional `SignerIcons` (the arrows) mirror themselves right to left: never flip them again here.
 */
@Composable
internal fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = SignerTheme.colors
    val style = SignerTheme.typography.subheadline.semibold()
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = SignerMetrics.minTouchTarget),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent, disabledContentColor = colors.tertiaryLabel),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(iconSizeFor(style, 1.2f)))
            Text(text, style = style, textAlign = TextAlign.Center)
            // A direction arrow ("Recovery Cards →") points the reading direction: SignerIcons.ArrowForward auto-mirrors.
            if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(iconSizeFor(style, 0.93f)))
        }
    }
}

/**
 * The size of an icon beside text of [style]: [ratio] × its font size, so it grows with the font scale like the text
 * (iOS SF Symbols follow Dynamic Type). At the default size it is the usual 14–24 dp.
 */
@Composable
@ReadOnlyComposable
internal fun iconSizeFor(style: TextStyle, ratio: Float): Dp = with(LocalDensity.current) { (style.fontSize * ratio).toDp() }

/**
 * The title row of a setup sheet (iOS sheet `NavigationStack` with an inline title and a "Done" confirmation action):
 * the title centred, "Done" at the end.
 */
@Composable
internal fun SheetTitleBar(title: String, onDone: () -> Unit, doneTag: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = title,
            style = SignerTheme.typography.headline,
            color = SignerTheme.colors.label,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.widthIn(max = 260.dp).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) {
            TextAction(stringResource(L10n.string.done), onClick = onDone, emphasized = true, modifier = Modifier.testTag(doneTag))
        }
    }
}
