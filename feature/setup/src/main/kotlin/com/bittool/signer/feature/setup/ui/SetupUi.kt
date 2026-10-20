package com.bittool.signer.feature.setup.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.ui.text.asString
import kotlin.math.roundToInt
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
 * The page bar of the setup steps and the Recovery Phrase page: the styling of `SignerTopAppBar` (page colour, lifted
 * when content scrolls under it, inline `headline` title) with test tags on its navigation icon (`nav.back` /
 * `setup.cancel`), which the shared bar has no hook for.
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
    val colors = SignerTheme.colors
    TopAppBar(
        title = {
            Text(
                text = title,
                style = SignerTheme.typography.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (navigation != SetupNavigation.None) {
                IconButton(onClick = onNavigate, enabled = navigationEnabled, modifier = Modifier.testTag(navigationTag)) {
                    Icon(
                        imageVector = if (navigation == SetupNavigation.Back) SignerIcons.ArrowBack else SignerIcons.Close,
                        contentDescription = navigationLabel,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.page,
            scrolledContainerColor = if (colors.isDark) colors.card else MaterialTheme.colorScheme.surfaceContainerHigh,
            navigationIconContentColor = colors.accent,
            titleContentColor = colors.label,
            actionIconContentColor = colors.accent,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/** The trailing "Cancel" of pushed steps (iOS `.topBarTrailing`). */
@Composable
internal fun CancelAction(enabled: Boolean, onClick: () -> Unit) {
    TextAction(stringResource(L10n.string.cancel), onClick = onClick, enabled = enabled, modifier = Modifier.testTag(CANCEL_TAG))
}

internal const val NAV_BACK_TAG = "nav.back"
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
}

/**
 * The shared layout of setup steps 1–4 (iOS `SetupScaffold`, setup-backup §5): a scrolling column 20 dp in, at most
 * 640 dp wide, starting with the step header (segmented progress, "Step n of total", large title, message — one
 * accessibility element), then [content] 20 dp apart; [footer] sits in the bottom action bar above the navigation bar
 * and the keyboard. [footerBottomPadding] 0 lets an IME-attached row sit flush on the keyboard.
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
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { topBar(scrollBehavior) },
        bottomBar = {
            BottomActionBar(
                canScrollUnder = scrollState.canScrollForward,
                bottomPadding = footerBottomPadding,
                maxWidth = footerMaxWidth,
                content = footer,
            )
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
                .verticalScroll(scrollState),
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

/** The step header: progress, "Step %lld of %lld", title and message as one element (setup-backup §5, §12.1). */
@Composable
private fun StepHeader(step: Int, total: Int, title: String, message: String) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StepProgress(step, total, Modifier.padding(bottom = 6.dp))
        Text(stringResource(L10n.string.step_x_of_x, step, total), style = typography.footnote.semibold(), color = colors.accent)
        Text(title, style = typography.largeTitle, color = colors.label, modifier = Modifier.semantics { heading() })
        Text(message, style = typography.body, color = colors.secondaryLabel)
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

/** A footer error (iOS `Callout(verbatim: error, tone: .danger)`): engine text keeps its own direction. */
@Composable
internal fun ErrorCallout(error: UiText?, modifier: Modifier = Modifier) {
    if (error == null) return
    Callout(text = error.asString(), modifier = modifier.testTag("setup.error"), tone = Tone.Danger, verbatim = error is UiText.Verbatim)
}

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
