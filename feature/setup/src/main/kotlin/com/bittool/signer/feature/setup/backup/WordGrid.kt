package com.bittool.signer.feature.setup.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bittool.signer.core.designsystem.theme.SignerMonospace
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.SignerTypography
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The result of [WordGridColumns.layout]: how many columns and the one word size every cell uses. */
@Immutable
internal data class WordGridLayout(val columns: Int, val wordSize: TextUnit) {
    /** The shared word font: monospaced semibold at [wordSize], laid out left to right in every language. */
    fun wordStyle(base: TextStyle): TextStyle =
        base.copy(fontFamily = SignerMonospace, fontWeight = FontWeight.SemiBold, fontSize = wordSize, lineHeight = TextUnit.Unspecified).ltr()
}

/**
 * The recovery word-grid rule (iOS `WordGridColumns`, `Features/Setup/RecoveryPhraseViews.swift:56-99`, setup-backup
 * §8.4.1): at most three columns and **one** word size for every cell, so each word is shown in full and all words look
 * alike. The size is the subheadline size at the current font scale, reduced just enough for the widest word to fit;
 * below 80 % the grid drops a column instead.
 *
 * Measured with a [TextMeasurer] in pixels of [Density] (the font scale included, so Android's non-linear font scaling
 * applies): `cell = (width − 8·(c−1)) / c`, `available = cell − 2·cellPadding − index − spacing − 3`,
 * `scale = min(1, available / widest)`, accepted when `scale ≥ 0.8` or `c == 1`, word size rounded down to half a point.
 */
@Immutable
internal data class WordGridColumns(
    val cellPadding: Dp,
    /** The widest index label ("24", "24."). */
    val indexSample: String,
    /** Index text style (caption2 for the word grid and the cards, caption for the backup check). */
    val indexStyle: TextStyle,
    val indexMinWidth: Dp = 0.dp,
    val spacing: Dp = 6.dp,
) {
    fun layout(width: Int, words: List<String>, measurer: TextMeasurer, density: Density, base: TextUnit = BASE_SIZE): WordGridLayout {
        if (width <= 0) {
            // Before layout (iOS width 0): the old default. Compose measures first, so this is only a safety net.
            return WordGridLayout(if (density.fontScale >= ACCESSIBILITY_FONT_SCALE) 2 else 3, base)
        }
        val wordStyle = WordGridLayout(3, base).wordStyle(TextStyle.Default)
        val widest = words.maxOfOrNull { measure(measurer, it, wordStyle, density) } ?: 0f
        val index = with(density) {
            max(indexMinWidth.toPx(), measure(measurer, indexSample, indexStyle.monospacedDigit(), density))
        }
        val grid = with(density) { GRID_SPACING.toPx() }
        val padding = with(density) { cellPadding.toPx() }
        val gap = with(density) { spacing.toPx() }
        val margin = with(density) { SAFETY_MARGIN.toPx() }
        for (columns in 3 downTo 1) {
            val cell = (width - grid * (columns - 1)) / columns
            val available = cell - 2 * padding - index - gap - margin
            val scale = if (widest > 0f) min(1f, available / widest) else 1f
            if (scale >= MINIMUM_SCALE || columns == 1) {
                val size = max(1f, floor(base.value * scale * 2f) / 2f)
                return WordGridLayout(columns, size.sp)
            }
        }
        return WordGridLayout(1, base)
    }

    fun count(width: Int, words: List<String>, measurer: TextMeasurer, density: Density): Int =
        layout(width, words, measurer, density).columns

    private fun measure(measurer: TextMeasurer, text: String, style: TextStyle, density: Density): Float =
        ceil(measurer.measure(text, style, softWrap = false, maxLines = 1, density = density).size.width.toFloat())

    companion object {
        /** iOS `UIFont.preferredFont(forTextStyle: .subheadline)` at the default size. */
        val BASE_SIZE: TextUnit = SignerTypography.Default.subheadline.fontSize
        val GRID_SPACING: Dp = 8.dp
        const val MINIMUM_SCALE = 0.8f
        val SAFETY_MARGIN: Dp = 3.dp

        /** iOS `dynamicTypeSize.isAccessibilitySize` (`SignerTheme.ACCESSIBILITY_FONT_SCALE`). */
        const val ACCESSIBILITY_FONT_SCALE = SignerTheme.ACCESSIBILITY_FONT_SCALE

        /** The revealed words and the concealed placeholder (which measures only "wwwwwwww"). */
        val Words = WordGridColumns(cellPadding = 10.dp, indexSample = "24", indexStyle = SignerTypography.Default.caption2, indexMinWidth = 18.dp)

        /** One recovery card (words plus the "xxxx" of left-out positions). */
        val Cards = WordGridColumns(cellPadding = 8.dp, indexSample = "24.", indexStyle = SignerTypography.Default.caption2, indexMinWidth = 20.dp)

        /** The backup check (the chip words plus "••••••"; the masked words never affect the layout). */
        val Verification = WordGridColumns(cellPadding = 8.dp, indexSample = "24.", indexStyle = SignerTypography.Default.caption)

        /** The reference word of the concealed placeholder: its columns leak nothing but the word count. */
        const val REFERENCE_WORD = "wwwwwwww"
    }
}

/**
 * Measures the available width, computes [columns]'s layout for [measuredWords] and lays [count] cells out in rows of
 * equal height, 8 dp apart ([cell] gets the shared layout). A manual grid: at most 24 cells, inside a vertical scroll.
 */
@Composable
internal fun WordGrid(
    count: Int,
    columns: WordGridColumns,
    measuredWords: List<String>,
    modifier: Modifier = Modifier,
    cell: @Composable (index: Int, layout: WordGridLayout) -> Unit,
) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val layout = remember(width, measuredWords, density, columns) { columns.layout(width, measuredWords, measurer, density) }
        Column(verticalArrangement = Arrangement.spacedBy(WordGridColumns.GRID_SPACING)) {
            for (rowStart in 0 until count step layout.columns) {
                key(rowStart) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(WordGridColumns.GRID_SPACING),
                    ) {
                        for (column in 0 until layout.columns) {
                            val index = rowStart + column
                            if (index < count) {
                                Box(Modifier.weight(1f).fillMaxHeight()) { cell(index, layout) }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
