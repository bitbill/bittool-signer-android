package com.bittool.signer.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * Layout metrics (design-system.md §4, iOS `Metrics`, `Design/DesignSystem.swift:24-45`). iOS points map 1:1 to dp.
 * Access through `SignerTheme.metrics`.
 */
@Immutable
object SignerMetrics {
    /** `Metrics.pagePadding`: horizontal page gutter, bottom-bar gutter, sheet padding. */
    val pagePadding = 20.dp

    /** `cardBackground(padding: 16)`: the default card inset. */
    val cardPadding = 16.dp

    /** Review summary card padding. */
    val cardPaddingLarge = 18.dp

    /** Gap between stacked cards (wallet list, review). */
    val stack = 16.dp

    /** Row spacing inside `BottomActionBar`. */
    val barGap = 10.dp

    /** `listBottomInset()`: room under a scrolling list's last row. */
    val listBottomInset = 30.dp

    /** `Metrics.readableWidth` and its narrower variants. */
    val readableWidth = 640.dp
    val narrowWidth = 520.dp
    val resultWidth = 560.dp
    val lockWidth = 440.dp

    /** Minimum touch target (Android; iOS uses 44). */
    val minTouchTarget = 48.dp

    /** Primary / secondary button height. */
    val buttonHeight = 52.dp
}

/** Corner radii (design-system §4.2). Cards use [RoundedCornerShape]; the brand tile uses `SuperellipseShape`. */
@Immutable
object SignerRadius {
    /** Default card, wallet card, matching-error card, scanner status panel. */
    val card = 22.dp

    /** Review summary card, scan home card. */
    val summary = 26.dp

    /** QR plate and its loading placeholder. */
    val qr = 24.dp

    /** Scan option tiles. */
    val tile = 20.dp

    /** Chain-picker track. */
    val track = 18.dp

    /** `Callout`, contract notice, acknowledgement box, summary inner boxes. */
    val callout = 16.dp

    /** Password field box, `ProfileMenu`, `AddressIndexControl`, chain-picker tiles. */
    val control = 14.dp
}

/** Shapes built from [SignerRadius]. */
@Immutable
object SignerShapes {
    val card = RoundedCornerShape(SignerRadius.card)
    val summary = RoundedCornerShape(SignerRadius.summary)
    val qr = RoundedCornerShape(SignerRadius.qr)
    val tile = RoundedCornerShape(SignerRadius.tile)
    val track = RoundedCornerShape(SignerRadius.track)
    val callout = RoundedCornerShape(SignerRadius.callout)
    val control = RoundedCornerShape(SignerRadius.control)
    val capsule = CircleShape

    /** Material shapes for stock components (dialogs 28 → card radius family, menus 14). */
    internal val material = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(SignerRadius.control),
        medium = RoundedCornerShape(SignerRadius.callout),
        large = RoundedCornerShape(SignerRadius.card),
        extraLarge = RoundedCornerShape(28.dp),
    )
}
