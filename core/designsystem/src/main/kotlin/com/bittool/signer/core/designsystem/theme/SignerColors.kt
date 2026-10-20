package com.bittool.signer.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The BitTool Signer palette (design-system.md §2, §9.2). Surfaces are the iOS grouped system colours
 * (`Design/DesignSystem.swift:14-22`), the accent is the asset-catalogue `AccentColor` (emerald `#0F7B55` light, mint
 * `#6BE3A4` dark) and the tones are the iOS 17/18 system green / orange / red, pinned.
 *
 * Three palettes exist: [Light], [Dark] and [DarkElevated]. iOS raises the grouped colours one level inside sheets and
 * popovers in dark mode; Android does the same through `ElevatedSurfaceLevel`, which every `SignerBottomSheet` and
 * `SignerAlertDialog` applies to its content (otherwise a sheet would be black on black, design-system §9.13 item 3).
 *
 * Contrast rule (design-system §2.4, §9.2): icons and fills keep the iOS tone colour, but **small text drawn on a
 * 12–14 % tint of a tone uses the matching `*OnTint` colour** ([accentOnTint], [successOnTint], [warningOnTint],
 * [dangerOnTint]); iOS draws tone-coloured text there, which falls to ≈ 1.8 : 1 for orange in light mode. Use
 * [onTint] to map a tone to its text colour.
 */
@Immutable
class SignerColors(
    /** `true` for both dark palettes. */
    val isDark: Boolean,
    /** `true` for the raised (sheet / dialog) dark palette. */
    val isElevated: Boolean,
    /** Page background: iOS `systemGroupedBackground` (`Palette.background`). */
    val page: Color,
    /** Card on the page: iOS `secondarySystemGroupedBackground` (`Palette.card`). */
    val card: Color,
    /** Inner boxes of a card and pressed rows: iOS `tertiarySystemGroupedBackground` (`Palette.raised`). */
    val raised: Color,
    /** The card colour of the elevated level (sheets, dialogs): white in light, `#2C2C2E` in dark. */
    val elevatedCard: Color,
    /** Global tint: links, toolbar items, toggles, list icons, progress, selection outlines, hero glyphs. */
    val accent: Color,
    /** Small text on an accent tint (12–14 %): `#0B6E4B` in light (≥ 4.6 : 1), the accent itself in dark. */
    val accentOnTint: Color,
    /** Label drawn on a solid accent fill (`Brand.onAccent`): white on emerald, ink on mint. */
    val onAccent: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val quaternaryLabel: Color,
    /** Translucent hairline (iOS `separator`). */
    val separator: Color,
    /** Opaque hairline for separators drawn on cards (iOS `opaqueSeparator`). */
    val opaqueSeparator: Color,
    /** iOS `systemFill`: strength-meter track, onboarding dots, concealed placeholders. */
    val fill: Color,
    val secondaryFill: Color,
    /** iOS `tertiarySystemFill`: chain-picker track, index field, segmented-control track. */
    val tertiaryFill: Color,
    val quaternaryFill: Color,
    /** iOS `systemGray5`: logo and token fallbacks. */
    val gray5: Color,
    val success: Color,
    val successOnTint: Color,
    val warning: Color,
    val warningOnTint: Color,
    val danger: Color,
    val dangerOnTint: Color,
    /** iOS `systemYellow`: strength level 2. */
    val strengthMid: Color,
    /** Segmented-control thumb (white in light, `#636366` in dark). */
    val segmentThumb: Color,
    /** `Brand.mint` `#B3F0C7`: the QR scanner's brackets, progress and buttons only (≠ the dark accent). */
    val mint: Color,
    /** `Brand.ink` `#0B1F16`: labels on [mint] and on the dark accent. */
    val ink: Color,
    /** App icon / brand tile ground `#0A0A0C`: never a UI colour. */
    val brandInk: Color,
    /** Dim around the scanner viewfinder while the camera runs (black 55 %). */
    val scannerScrim: Color,
    /** The QR plate is white and its modules black in both themes (design-system §2.6). */
    val qrPlate: Color,
    val qrModule: Color,
    /** `LaunchBackground.colorset`: also the splash, window and recents placeholder colour. */
    val launchBackground: Color,
) {
    /**
     * The colour for small text or a small glyph drawn on a 12–14 % tint of [tone]: the `*OnTint` variant of a
     * semantic tone, [secondaryLabel] for the secondary tone, otherwise [tone] unchanged.
     */
    fun onTint(tone: Color): Color = when (tone) {
        accent -> accentOnTint
        success -> successOnTint
        warning -> warningOnTint
        danger -> dangerOnTint
        else -> tone
    }

    /** The same palette one level up (sheets, dialogs); identity for light and for an already elevated palette. */
    fun elevated(): SignerColors = if (isDark && !isElevated) DarkElevated else this

    override fun toString(): String = when {
        !isDark -> "SignerColors.Light"
        isElevated -> "SignerColors.DarkElevated"
        else -> "SignerColors.Dark"
    }

    companion object {
        private val emerald = Color(0xFF0F7B55)
        private val mintAccent = Color(0xFF6BE3A4)
        private val scannerMint = Color(0xFFB3F0C7)
        private val inkLabel = Color(0xFF0B1F16)
        private val tileInk = Color(0xFF0A0A0C)

        val Light = SignerColors(
            isDark = false,
            isElevated = false,
            page = Color(0xFFF2F2F7),
            card = Color(0xFFFFFFFF),
            raised = Color(0xFFF2F2F7),
            elevatedCard = Color(0xFFFFFFFF),
            accent = emerald,
            accentOnTint = Color(0xFF0B6E4B),
            onAccent = Color(0xFFFFFFFF),
            label = Color(0xFF000000),
            secondaryLabel = Color(0x993C3C43),
            tertiaryLabel = Color(0x4D3C3C43),
            quaternaryLabel = Color(0x2E3C3C43),
            separator = Color(0x4A3C3C43),
            opaqueSeparator = Color(0xFFC6C6C8),
            fill = Color(0x33787880),
            secondaryFill = Color(0x29787880),
            tertiaryFill = Color(0x1F767680),
            quaternaryFill = Color(0x14747480),
            gray5 = Color(0xFFE5E5EA),
            success = Color(0xFF34C759),
            successOnTint = Color(0xFF1B7333),
            warning = Color(0xFFFF9500),
            warningOnTint = Color(0xFFB33000),
            danger = Color(0xFFFF3B30),
            dangerOnTint = Color(0xFFC2000F),
            strengthMid = Color(0xFFFFCC00),
            segmentThumb = Color(0xFFFFFFFF),
            mint = scannerMint,
            ink = inkLabel,
            brandInk = tileInk,
            scannerScrim = Color(0x8C000000),
            qrPlate = Color.White,
            qrModule = Color.Black,
            launchBackground = Color(0xFFF2F2F7),
        )

        val Dark = SignerColors(
            isDark = true,
            isElevated = false,
            page = Color(0xFF000000),
            card = Color(0xFF1C1C1E),
            raised = Color(0xFF2C2C2E),
            elevatedCard = Color(0xFF2C2C2E),
            accent = mintAccent,
            accentOnTint = mintAccent,
            onAccent = inkLabel,
            label = Color(0xFFFFFFFF),
            secondaryLabel = Color(0x99EBEBF5),
            tertiaryLabel = Color(0x4DEBEBF5),
            quaternaryLabel = Color(0x29EBEBF5),
            separator = Color(0x99545458),
            opaqueSeparator = Color(0xFF38383A),
            fill = Color(0x5C787880),
            secondaryFill = Color(0x52787880),
            tertiaryFill = Color(0x3D767680),
            quaternaryFill = Color(0x2E767680),
            gray5 = Color(0xFF2C2C2E),
            success = Color(0xFF30D158),
            successOnTint = Color(0xFF30D158),
            warning = Color(0xFFFF9F0A),
            warningOnTint = Color(0xFFFF9F0A),
            danger = Color(0xFFFF453A),
            dangerOnTint = Color(0xFFFF7A72), // #FF6961 of the spec is 4.3 : 1 on the 14 % tint over #2C2C2E
            strengthMid = Color(0xFFFFD60A),
            segmentThumb = Color(0xFF636366),
            mint = scannerMint,
            ink = inkLabel,
            brandInk = tileInk,
            scannerScrim = Color(0x8C000000),
            qrPlate = Color.White,
            qrModule = Color.Black,
            launchBackground = Color(0xFF000000),
        )

        /** iOS dark "elevated" interface level: sheets and popovers lift every grouped colour one step. */
        val DarkElevated = SignerColors(
            isDark = true,
            isElevated = true,
            page = Color(0xFF1C1C1E),
            card = Color(0xFF2C2C2E),
            raised = Color(0xFF3A3A3C),
            elevatedCard = Color(0xFF2C2C2E),
            accent = Dark.accent,
            accentOnTint = Dark.accentOnTint,
            onAccent = Dark.onAccent,
            label = Dark.label,
            secondaryLabel = Dark.secondaryLabel,
            tertiaryLabel = Dark.tertiaryLabel,
            quaternaryLabel = Dark.quaternaryLabel,
            separator = Dark.separator,
            opaqueSeparator = Color(0xFF48484A),
            fill = Dark.fill,
            secondaryFill = Dark.secondaryFill,
            tertiaryFill = Dark.tertiaryFill,
            quaternaryFill = Dark.quaternaryFill,
            gray5 = Color(0xFF3A3A3C),
            success = Dark.success,
            successOnTint = Dark.successOnTint,
            warning = Dark.warning,
            warningOnTint = Dark.warningOnTint,
            danger = Dark.danger,
            dangerOnTint = Dark.dangerOnTint,
            strengthMid = Dark.strengthMid,
            segmentThumb = Color(0xFF636366),
            mint = Dark.mint,
            ink = Dark.ink,
            brandInk = Dark.brandInk,
            scannerScrim = Dark.scannerScrim,
            qrPlate = Dark.qrPlate,
            qrModule = Dark.qrModule,
            launchBackground = Dark.launchBackground,
        )
    }
}

/**
 * The Material 3 scheme behind stock components (design-system §9.2 table). No dynamic colour: the brand palette is
 * fixed. `surfaceTint` equals the surface so tonal elevation never tints anything.
 */
internal fun SignerColors.toColorScheme(): ColorScheme = when {
    !isDark -> lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = Color(0xFFDDEDE7),
        onPrimaryContainer = accentOnTint,
        inversePrimary = Color(0xFF6BE3A4),
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = Color(0xFFDDEDE7),
        onSecondaryContainer = accentOnTint,
        tertiary = warningOnTint,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFF0DB),
        onTertiaryContainer = warningOnTint,
        error = danger,
        onError = Color.White,
        errorContainer = Color(0xFFFFE4E2),
        onErrorContainer = dangerOnTint,
        background = page,
        onBackground = label,
        surface = page,
        onSurface = label,
        surfaceVariant = gray5,
        onSurfaceVariant = Color(0xFF8A8A8E),
        surfaceTint = page,
        inverseSurface = Color(0xFF1C1C1E),
        inverseOnSurface = Color.White,
        outline = Color(0xFF8E8E93),
        outlineVariant = opaqueSeparator,
        scrim = Color.Black,
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFE5E5EA),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F7FA),
        surfaceContainer = Color(0xFFF2F2F7),
        surfaceContainerHigh = Color(0xFFEBEBF0),
        surfaceContainerHighest = Color(0xFFE5E5EA),
    )
    isElevated -> darkScheme(this).copy(
        background = page,
        surface = page,
        surfaceTint = page,
        surfaceContainerLowest = Color(0xFF1C1C1E),
        surfaceContainerLow = Color(0xFF242426),
        surfaceContainer = Color(0xFF2C2C2E),
        surfaceContainerHigh = Color(0xFF3A3A3C),
        surfaceContainerHighest = Color(0xFF48484A),
        surfaceBright = Color(0xFF48484A),
        surfaceDim = Color(0xFF1C1C1E),
    )
    else -> darkScheme(this)
}

private fun darkScheme(colors: SignerColors): ColorScheme = with(colors) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = Color(0xFF273831),
        onPrimaryContainer = accentOnTint,
        inversePrimary = Color(0xFF0F7B55),
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = Color(0xFF273831),
        onSecondaryContainer = accentOnTint,
        tertiary = warning,
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF3C2E1B),
        onTertiaryContainer = warningOnTint,
        error = danger,
        onError = Color.White,
        errorContainer = Color(0xFF3C2222),
        onErrorContainer = dangerOnTint,
        background = page,
        onBackground = label,
        surface = page,
        onSurface = label,
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFF98989F),
        surfaceTint = page,
        inverseSurface = Color(0xFFF2F2F7),
        inverseOnSurface = Color.Black,
        outline = Color(0xFF8E8E93),
        outlineVariant = opaqueSeparator,
        scrim = Color.Black,
        surfaceBright = Color(0xFF3A3A3C),
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF141416),
        surfaceContainer = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF2C2C2E),
        surfaceContainerHighest = Color(0xFF3A3A3C),
    )
}
