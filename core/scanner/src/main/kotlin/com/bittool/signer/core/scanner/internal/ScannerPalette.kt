package com.bittool.signer.core.scanner.internal

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The scanner's fixed colours (scan-sign.md §19; design-system.md §2.2, §5.23). The scanner is always dark, in its own
 * subtree only (iOS `.environment(\.colorScheme, .dark)`, `QRScannerView.swift:48`); `:core:scanner` may not use
 * `:core:designsystem` tokens in Wave 1, so the iOS values are repeated here.
 */
internal object ScannerPalette {
    /** `Brand.mint`: brackets, progress, scanner buttons. */
    val Mint = Color(0xFFB3F0C7)

    /** `Brand.ink`: text on mint. */
    val Ink = Color(0xFF0B1F16)

    /** SwiftUI `.orange` in dark mode (`systemOrange` #FF9F0A): scanner problems. */
    val Orange = Color(0xFFFF9F0A)

    /** Dark `secondaryLabel` (#EBEBF5 at 60 %). */
    val Secondary = Color(0x99EBEBF5)

    /** The status panel: Android rendition of `.ultraThinMaterial` over the dimmed camera. */
    val Panel = Color.Black.copy(alpha = 0.62f)

    /** The glass circles of the top bar. */
    val Glass = Color.White.copy(alpha = 0.16f)

    val ColorScheme: ColorScheme = darkColorScheme(
        primary = Mint,
        onPrimary = Ink,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        onSurfaceVariant = Secondary,
    )
}
