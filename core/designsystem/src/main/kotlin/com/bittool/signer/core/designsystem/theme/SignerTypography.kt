package com.bittool.signer.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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

/**
 * SF Mono equivalent (`.monospaced()`). Identifiers drawn with it should also be [ltr]. For a translated sentence that
 * embeds an identifier ("Fingerprint %s"), also mark the identifier with [monospacedRun].
 */
fun TextStyle.monospaced(): TextStyle = copy(fontFamily = SignerMonospace)

/**
 * [text] with the monospaced family ([SignerMonospace]) as a span on [value] alone: its first occurrence, without the
 * bidi isolates or marks around it. For a translated sentence that embeds an identifier, drawn in a [monospaced] style
 * like iOS (`Text("Fingerprint \(value)").font(.caption.monospaced())`): the identifier keeps the monospaced face
 * whatever the sentence around it is drawn in.
 *
 * QA regression-10 read the Arabic "Fingerprint" line as drawing the value in a proportional face. Measured on the QA
 * emulator (API 31, `FingerprintFaceDeviceTest`), the value is drawn in the monospaced face with or without the span:
 * its glyphs in the QA screenshot sit exactly on the monospaced cells. Only the capitals and digits of the system's
 * monospaced face (Droid Sans Mono) look like an ordinary sans; in English the lower-case "Fingerprint" around them is
 * what makes the line look typewritten. [text] that does not contain [value] (or an empty [value]) comes back without
 * a span. Pure.
 */
fun monospacedRun(text: String, value: String): AnnotatedString {
    val start = if (value.isEmpty()) -1 else text.indexOf(value)
    if (start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(fontFamily = SignerMonospace), start, start + value.length)
    }
}

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

/**
 * Lays each paragraph out in the direction of its own first strong character, whatever the interface language: for
 * verbatim text that is not translated — engine warnings, decoded values, names from the registry, the English-only
 * legal documents. Compose otherwise forces the interface direction on every paragraph, so in Arabic an English
 * warning's full stop and brackets move to the wrong end (".every token of this contract"). Never for translated
 * strings: an Arabic sentence that starts with a Latin product name would be laid out left to right.
 */
fun TextStyle.contentDirection(): TextStyle = copy(textDirection = TextDirection.Content)

/**
 * The size of a glyph drawn beside text in this style, [ratio] × its font size as drawn (an SF Symbol follows Dynamic
 * Type the same way): a fixed `dp` icon would stay small next to text at large font scales. The ratio applies to the
 * drawn text size, after the font scale: Android 14+ scales large sizes less than small ones (non-linear font scaling),
 * so converting `ratio × fontSize` instead would draw the glyph smaller than its text. Never below the size it has at
 * the default font scale.
 */
@Composable
@ReadOnlyComposable
fun TextStyle.glyphSize(ratio: Float): Dp {
    if (!fontSize.isSp) return DEFAULT_GLYPH
    val density = LocalDensity.current
    val scaled = with(density) { fontSize.toDp() } * ratio
    val unscaled = (fontSize.value * ratio).dp
    return maxOf(scaled, unscaled)
}

private val DEFAULT_GLYPH = 24.dp

/**
 * For text that is translated where a translation exists and falls back to English where it does not (engine phrases
 * drawn through `ReviewText`, Android-only strings that have no translation yet): a right-to-left translation keeps
 * the interface direction, so an Arabic sentence that starts with "BitTool Signer" stays right to left; text without
 * any right-to-left letter (the English fallback) is laid out in its own direction ([contentDirection]), so in Arabic
 * its full stop stays at its end (QA sign-02). In a left-to-right interface this is [contentDirection].
 */
@Composable
@ReadOnlyComposable
fun TextStyle.localizedDirection(text: String): TextStyle = localizedDirection(text, LocalLayoutDirection.current)

/** [localizedDirection] for an explicit interface direction. Pure. */
fun TextStyle.localizedDirection(text: String, layoutDirection: LayoutDirection): TextStyle =
    if (layoutDirection == LayoutDirection.Rtl && containsRightToLeftLetters(text)) this else contentDirection()

/** Whether [text] has a strong right-to-left character (Hebrew, Arabic, Syriac, Thaana, N'Ko…). Pure. */
fun containsRightToLeftLetters(text: CharSequence): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}
