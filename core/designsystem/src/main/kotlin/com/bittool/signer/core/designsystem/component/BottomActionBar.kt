package com.bittool.signer.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.modifier.keepInViewAboveBar
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme

/**
 * Primary actions pinned above the navigation bar and the keyboard (iOS `.bottomActionBar`,
 * `Design/DesignSystem.swift:66-92`): a column of [content] 10 dp apart, 20 dp side gutters, 8 dp above, [bottomPadding]
 * below (8 by default; Welcome passes 0 because it ends in the legal-links line), constrained to [maxWidth] and centred.
 * The bar is opaque in the page colour; put it in a `Scaffold`'s `bottomBar` and let the list scroll beneath it.
 *
 * [canScrollUnder] (typically `scrollState.canScrollForward` / `listState.canScrollForward`) draws, only then, a 12 dp
 * fade above the bar and a hairline at its top edge: the Android rendition of the iOS 26 scroll-edge effect, so
 * content visibly continues under the buttons and the bar reads as a surface only when it covers something.
 *
 * The bar grows with the keyboard, over content that scrolls beneath it: give that scrolling content
 * [keepInViewAboveBar] with the scaffold's bottom padding, so a focused field and the line being typed stay visible
 * above the bar when the keyboard opens (QA setup-02).
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    maxWidth: Dp = SignerMetrics.readableWidth,
    bottomPadding: Dp = 8.dp,
    canScrollUnder: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SignerTheme.colors
    val edge by animateFloatAsState(if (canScrollUnder) 1f else 0f, SignerMotion.quick(), label = "barEdge")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val fade = FADE_HEIGHT.toPx()
                if (edge > 0f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to colors.page.copy(alpha = 0f),
                            1f to colors.page.copy(alpha = edge),
                            startY = -fade,
                            endY = 0f,
                        ),
                        topLeft = Offset(0f, -fade),
                        size = Size(size.width, fade),
                    )
                }
                drawRect(colors.page)
                if (edge > 0f) {
                    drawRect(colors.separator.copy(alpha = colors.separator.alpha * edge), size = Size(size.width, 1.dp.toPx() / 2f))
                }
            }
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(top = 8.dp, bottom = bottomPadding)
            .padding(horizontal = SignerMetrics.pagePadding)
            .readableWidth(maxWidth),
        verticalArrangement = Arrangement.spacedBy(SignerMetrics.barGap),
        content = content,
    )
}

private val FADE_HEIGHT = 12.dp
