package com.bittool.signer.feature.sign.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/**
 * The size of a symbol drawn in a text style (iOS `Image(systemName:).font(.title2)`): [base] at the default text size,
 * growing with the font scale like the text next to it ([style]'s font size × 1.2 when that is larger).
 */
@Composable
@ReadOnlyComposable
internal fun symbolSize(base: Dp, style: TextStyle): Dp {
    val scaled = with(LocalDensity.current) { (style.fontSize * 1.2f).toDp() }
    return maxOf(base, scaled)
}
