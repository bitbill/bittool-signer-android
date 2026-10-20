package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R

/** The leading navigation affordance of [SignerTopAppBar]. */
enum class NavIcon {
    /** Tab roots and pages without a way back from the bar. */
    None,

    /** Pushed pages: a mirrored back arrow labelled "Back". */
    Back,

    /** Modal pages and full-screen flows: a cross labelled "Close". */
    Close,
    ;

    /** The icon's default test tag ([NavIconTags]); null for [None]. */
    val testTag: String?
        get() = when (this) {
            None -> null
            Back -> NavIconTags.BACK
            Close -> NavIconTags.CLOSE
        }
}

/**
 * Test tags of the [SignerTopAppBar] navigation icon, Android-only e2e ids (gaps.md G1.0): the suite's `goBack` clicks
 * `nav.back` instead of pressing Back, so discard dialogs stay testable.
 */
object NavIconTags {
    const val BACK = "nav.back"
    const val CLOSE = "nav.close"
}

/**
 * The page bar (iOS `.navigationTitle` + toolbar): Material 3 `TopAppBar` on the page colour, a large title for tab
 * roots ([large], iOS large titles: `largeTitle` 34 / 41 bold, collapsing into the 17 sp semibold inline title) and an
 * inline `headline` title otherwise. The large bar grows with the font scale so its title is never clipped, and allows
 * a second line at accessibility text sizes. Scrolled content lifts the bar to `surfaceContainerHigh` (light) /
 * `card` (dark) through [scrollBehavior]. [actions] hold `TextAction`s and icon buttons; [navigation] decides the
 * leading icon, [onNavigate] handles it.
 *
 * The navigation icon carries the test tag [navigationTag] (default [NavIcon.testTag]: `nav.back` / `nav.close`), is
 * read as [navigationLabel] (default "Back" / "Close"; the first setup step's cross says "Cancel") and ignores taps
 * while not [navigationEnabled] (busy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignerTopAppBar(
    title: String,
    navigation: NavIcon = NavIcon.Back,
    onNavigate: () -> Unit = {},
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    large: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    navigationTag: String? = navigation.testTag,
    navigationLabel: String? = null,
    navigationEnabled: Boolean = true,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = colors.page,
        scrolledContainerColor = if (colors.isDark) colors.card else MaterialTheme.colorScheme.surfaceContainerHigh,
        navigationIconContentColor = colors.accent,
        titleContentColor = colors.label,
        actionIconContentColor = colors.accent,
    )
    val navigationIcon: @Composable () -> Unit = {
        if (navigation != NavIcon.None) {
            IconButton(
                onClick = onNavigate,
                enabled = navigationEnabled,
                modifier = navigationTag?.let { Modifier.testTag(it) } ?: Modifier,
            ) {
                Icon(
                    imageVector = if (navigation == NavIcon.Back) SignerIcons.ArrowBack else SignerIcons.Close,
                    contentDescription = navigationLabel
                        ?: stringResource(if (navigation == NavIcon.Back) R.string.back else R.string.close),
                )
            }
        }
    }
    if (large) {
        // Material draws the expanded title in headlineMedium and the collapsed one in titleLarge; iOS large titles are
        // `.largeTitle` bold (34 / 41) and collapse into the inline `.headline` title (17 semibold).
        val lines = if (SignerTheme.isAccessibilityTextSize) 2 else 1
        val expandedHeight = largeBarExpandedHeight(typography.largeTitle.lineHeight, lines)
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography.copy(headlineMedium = typography.largeTitle, titleLarge = typography.headline),
        ) {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = lines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                modifier = modifier,
                navigationIcon = navigationIcon,
                actions = actions,
                expandedHeight = expandedHeight,
                colors = barColors,
                scrollBehavior = scrollBehavior,
            )
        }
    } else {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = typography.headline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            },
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = barColors,
            scrollBehavior = scrollBehavior,
        )
    }
}

/**
 * The expanded height of the large bar: Material's 152 dp at the default size, which holds one 41 sp line above the
 * 28 dp title padding with 19 dp to spare under the 64 dp collapsed row, growing so [lines] lines of [lineHeight]
 * (scaled by the font setting) always fit (`TwoRowsTopAppBar` clips its title row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@ReadOnlyComposable
internal fun largeBarExpandedHeight(lineHeight: TextUnit, lines: Int): Dp {
    val line = with(LocalDensity.current) { lineHeight.toDp() }
    val needed = TopAppBarDefaults.LargeAppBarCollapsedHeight + LARGE_TITLE_BOTTOM_PADDING + LARGE_TITLE_TOP_ROOM + line * lines
    return maxOf(TopAppBarDefaults.LargeAppBarExpandedHeight, needed)
}

/** Material's `LargeTitleBottomPadding`. */
private val LARGE_TITLE_BOTTOM_PADDING = 28.dp
private val LARGE_TITLE_TOP_ROOM = 19.dp
