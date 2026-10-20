package com.bittool.signer.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The iOS Dynamic Type scale at its default ("Large") size, by iOS style name (design-system.md §3). iOS points map 1:1
 * to `sp`, so the Android font-size setting scales every style (Android 14+ applies non-linear scaling by itself).
 *
 * Line heights are the iOS leadings, applied with [LineHeightStyle.Mode.Minimum]: a line is never shorter than the
 * font's own height, so Thai, Vietnamese and Arabic marks are never clipped (design-system §9.3, pitfall 10), while
 * Latin text keeps the iOS rhythm. Letter spacing is 0 (SF Pro tracking is not reproduced on Roboto).
 *
 * Special faces are modifiers of a style: [monospaced] (every address, hash, path, fingerprint, payload),
 * [monospacedDigit] (counters, percentages, index labels), [semibold] / [bold] / [medium], [rounded] (review amount,
 * avatar initial — Android has no rounded system face, so this keeps the default family), [ltr] (identifiers in RTL).
 */
@Immutable
data class SignerTypography(
    /** 34 / 41, bold: Welcome title, onboarding titles, tab-root large titles. */
    val largeTitle: TextStyle,
    /** 28 / 34. */
    val title1: TextStyle,
    /** 22 / 28: "Signature Created", wallet name on the detail page, lock title (callers add bold). */
    val title2: TextStyle,
    /** 20 / 25: Welcome slogan, review text headline (semibold), activity detail title (bold). */
    val title3: TextStyle,
    /** 17 / 22, semibold: card titles ("How signing works", "Details"), wallet names, scanner title. */
    val headline: TextStyle,
    /** 17 / 22: row titles, default text; buttons use [body] semibold. */
    val body: TextStyle,
    /** 16 / 21: full addresses in summaries, flow lines, token IDs, legal paragraphs. */
    val callout: TextStyle,
    /** 15 / 20: callout text, step rows, list subtitles, compact action labels. */
    val subheadline: TextStyle,
    /** 13 / 18: detail values, address rows, footers, password requirements. */
    val footnote: TextStyle,
    /** 12 / 16: detail labels, pills, field captions, fingerprints. */
    val caption: TextStyle,
    /** 11 / 13: payload text, support badges, contract captions. */
    val caption2: TextStyle,
) {
    /** The Material 3 roles stock components read (design-system §9.3 mapping). */
    internal fun toMaterial(): Typography {
        val base = Typography()
        return Typography(
            displayLarge = base.displayLarge.merge(baseStyle),
            displayMedium = base.displayMedium.merge(baseStyle),
            displaySmall = largeTitle.bold(),
            headlineLarge = largeTitle.bold(),
            headlineMedium = title1,
            headlineSmall = title2,
            titleLarge = title3,
            titleMedium = headline,
            titleSmall = subheadline.semibold(),
            bodyLarge = body,
            bodyMedium = subheadline,
            bodySmall = footnote,
            labelLarge = callout.semibold(),
            labelMedium = caption.semibold(),
            labelSmall = caption2.semibold(),
        )
    }

    companion object {
        /** Base of every style: the system font, no letter spacing, no font padding, minimum line height. */
        internal val baseStyle = TextStyle(
            fontFamily = FontFamily.Default,
            letterSpacing = 0.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
                mode = LineHeightStyle.Mode.Minimum,
            ),
        )

        private fun style(size: TextUnit, leading: TextUnit, weight: FontWeight = FontWeight.Normal) =
            baseStyle.copy(fontSize = size, lineHeight = leading, fontWeight = weight)

        val Default = SignerTypography(
            largeTitle = style(34.sp, 41.sp, FontWeight.Bold),
            title1 = style(28.sp, 34.sp),
            title2 = style(22.sp, 28.sp),
            title3 = style(20.sp, 25.sp),
            headline = style(17.sp, 22.sp, FontWeight.SemiBold),
            body = style(17.sp, 22.sp),
            callout = style(16.sp, 21.sp),
            subheadline = style(15.sp, 20.sp),
            footnote = style(13.sp, 18.sp),
            caption = style(12.sp, 16.sp),
            caption2 = style(11.sp, 13.sp),
        )
    }
}

/**
 * The one monospaced family of the app. `FontFamily.Monospace` resolves to the device's mono face; bundling a mono font
 * with unambiguous 0/O and 1/l/I glyphs (design-system §9.3 recommendation) needs an Android-only licence notice and is
 * an owner decision — change it here only.
 */
val SignerMonospace: FontFamily = FontFamily.Monospace

/** SF Mono equivalent (`.monospaced()`). Identifiers drawn with it should also be [ltr]. */
fun TextStyle.monospaced(): TextStyle = copy(fontFamily = SignerMonospace)

/** Tabular figures (`.monospacedDigit()`): counters and percentages that must not jitter. */
fun TextStyle.monospacedDigit(): TextStyle = copy(fontFeatureSettings = "tnum")

fun TextStyle.medium(): TextStyle = copy(fontWeight = FontWeight.Medium)
fun TextStyle.semibold(): TextStyle = copy(fontWeight = FontWeight.SemiBold)
fun TextStyle.bold(): TextStyle = copy(fontWeight = FontWeight.Bold)

/** SwiftUI `design: .rounded` has no Android system face; the default family keeps the weight and size. */
fun TextStyle.rounded(): TextStyle = this

/**
 * Lays the text out left to right whatever the interface language (design-system §9.3, pitfall 11): addresses,
 * hashes, paths, payloads, fingerprints, counters. Breaks only at spaces (the group separators), never hyphenates.
 */
fun TextStyle.ltr(): TextStyle = copy(textDirection = TextDirection.Ltr, lineBreak = LineBreak.Simple)
