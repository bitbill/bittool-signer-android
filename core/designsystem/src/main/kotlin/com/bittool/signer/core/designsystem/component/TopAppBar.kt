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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
}

/**
 * The page bar (iOS `.navigationTitle` + toolbar): Material 3 `TopAppBar` on the page colour, a large title for tab
 * roots ([large], iOS large titles, `headlineLarge` 34 bold) and an inline `headline` title otherwise. Scrolled content
 * lifts the bar to `surfaceContainerHigh` (light) / `card` (dark) through [scrollBehavior]. [actions] hold
 * `TextAction`s and icon buttons; [navigation] decides the leading icon, [onNavigate] handles it.
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
        when (navigation) {
            NavIcon.None -> Unit
            NavIcon.Back -> IconButton(onClick = onNavigate) {
                Icon(SignerIcons.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            NavIcon.Close -> IconButton(onClick = onNavigate) {
                Icon(SignerIcons.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
    if (large) {
        LargeTopAppBar(
            title = {
                Text(
                    text = title,
                    maxLines = 2,
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
