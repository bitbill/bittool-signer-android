package com.bittool.signer.feature.wallets.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.StatusToastState
import com.bittool.signer.core.designsystem.haptics.HapticKind
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.pressScale
import com.bittool.signer.core.designsystem.theme.SignerRadius
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.ui.wallet.WalletIdentityHeader

/** Position of a row inside a lazily built grouped section: decides which corners are rounded. */
internal enum class RowPosition {
    Single, First, Middle, Last;

    companion object {
        fun of(index: Int, count: Int): RowPosition = when {
            count <= 1 -> Single
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

/** The card shape of a grouped row at [position] (outer corners [SignerRadius.card], inner corners square). */
internal fun RowPosition.shape(): Shape {
    val r = SignerRadius.card
    return when (this) {
        RowPosition.Single -> RoundedCornerShape(r)
        RowPosition.First -> RoundedCornerShape(topStart = r, topEnd = r)
        RowPosition.Middle -> RoundedCornerShape(0.dp)
        RowPosition.Last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
    }
}

/**
 * One row of an inset-grouped section built from `LazyColumn` items (the `GroupedSection` look for lists too long to
 * compose at once, e.g. 2³¹ receive addresses): the `card` colour clipped to the section's outer corners and, under every
 * row but the last, the opaque hairline inset by [dividerInset] (mirrored in right-to-left layouts).
 */
@Composable
internal fun GroupedLazyRow(
    position: RowPosition,
    modifier: Modifier = Modifier,
    dividerInset: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = SignerTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val divider = position == RowPosition.First || position == RowPosition.Middle
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(position.shape())
            .background(colors.card)
            .then(
                if (divider) {
                    Modifier.drawBehind {
                        val inset = dividerInset.toPx()
                        val stroke = 0.5.dp.toPx()
                        val y = size.height - stroke / 2
                        val start = if (rtl) 0f else inset
                        val end = if (rtl) size.width - inset else size.width
                        drawLine(colors.opaqueSeparator, Offset(start, y), Offset(end, y), stroke)
                    }
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/** A section header outside the card (the `GroupedSection` header style: subheadline semibold, `accentOnTint`). */
@Composable
internal fun GroupedHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SignerTheme.typography.subheadline.semibold(),
        color = SignerTheme.colors.accentOnTint,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A section footer under the card (the `GroupedSection` footer style: footnote, secondary). */
@Composable
internal fun GroupedFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SignerTheme.typography.footnote,
        color = SignerTheme.colors.secondaryLabel,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/**
 * The wallet a security page acts on, in its own card (iOS `Section { WalletIdentityHeader(walletID:) }` of the
 * Change Password / Delete / Repair forms): shown first so the person recognises the wallet before typing a password.
 */
@Composable
internal fun WalletIdentityCard(identity: WalletIdentity?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(SignerShapes.card)
            .background(SignerTheme.colors.card)
            .padding(16.dp),
    ) {
        WalletIdentityHeader(
            name = identity?.name.orEmpty(),
            fingerprint = identity?.fingerprint,
            avatarIdentity = identity?.avatarIdentity ?: identity?.id?.uppercase.orEmpty(),
            size = size,
        )
    }
}

/**
 * The destructive call to action of the Delete Wallet page (iOS `.borderedProminent` capsule tinted `Palette.danger`
 * with a white semibold label, `controlSize(.large)`, `WalletSecuritySheets.swift:224-241`): the geometry of
 * `PrimaryButton` (52 dp capsule, 20 dp icon, label kept laid out under the spinner) in the danger tone. Disabled it
 * takes the neutral disabled fill of the design system's buttons.
 */
@Composable
internal fun DestructiveButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = SignerTheme.colors
    val interaction = remember { MutableInteractionSource() }
    // iOS draws the label white on the danger tint in both appearances (`.foregroundStyle(.white)`).
    val content = if (enabled) Color.White else colors.secondaryLabel
    Surface(
        onClick = { if (!busy) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SignerTheme.metrics.buttonHeight)
            .pressScale(interaction, scale = 0.97f, alpha = 1f)
            .semantics { if (busy) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        enabled = enabled,
        shape = SignerShapes.capsule,
        color = if (enabled) colors.danger else colors.label.copy(alpha = 0.12f),
        contentColor = content,
        interactionSource = interaction,
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.alpha(if (busy) 0f else 1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
                Text(
                    text = text,
                    style = SignerTheme.typography.body.semibold(),
                    color = content,
                    textAlign = TextAlign.Center,
                    maxLines = if (SignerTheme.isAccessibilityTextSize) 2 else 1,
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).clearAndSetSemantics {},
                    color = content,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * Shows the overlay notice of a refused decisive touch (gaps.md G4.3, ARCHITECTURE §4.8): the
 * `security_overlay_blocked` toast with the warning glyph and the warning haptic — a refusal, not a success.
 */
@Composable
internal fun rememberOverlayBlockedNotice(toast: StatusToastState): () -> Unit {
    val text = stringResource(R.string.security_overlay_blocked)
    return remember(toast, text) { { toast.show(text, SignerIcons.WarningFilled, HapticKind.Warning) } }
}

/**
 * A QR code with the Android-only brightness switch on its top-end corner (wallets-pairing.md §13.5, the Ownbit
 * `QrBrightnessCorner` product decision): pairing pages never change the brightness by themselves (iOS never does);
 * switched to "Bright" the window runs at full brightness while the code is shown, and leaving the page (or switching
 * back) restores it. The capsule is centred on the always-white plate's top edge and inset from its top-end corner by
 * the plate's corner radius: its lower half lies in the 18 dp quiet zone, so it never covers a module, and it never
 * reaches past the plate's end edge into the page gutter (QA wallets-06). It is drawn in the light palette like the
 * plate. Starts at "Normal" on every visit (a configuration change keeps the choice).
 *
 * [content] draws the code (a `QrCodeView` at most [maxSide] wide) and receives whether full brightness is wanted.
 * [enabled] false hides the switch (the code is still loading).
 */
@Composable
internal fun QrBrightnessCorner(
    maxSide: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (bright: Boolean) -> Unit,
) {
    var bright by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxSide).fillMaxWidth()) {
            content(enabled && bright)
            if (enabled) {
                QrBrightnessToggle(
                    bright = bright,
                    onBrightChange = { bright = it },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Centred on the plate's top edge, where the end corner's curve has ended (mirrored in RTL).
                        .offset(x = -QR_PLATE_CORNER_RADIUS, y = -(BRIGHTNESS_TARGET / 2)),
                )
            }
        }
    }
}

/** The "Normal / Bright" capsule (48 dp touch target around a 26 dp capsule). */
@Composable
internal fun QrBrightnessToggle(bright: Boolean, onBrightChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberSignerHaptics()
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(if (bright) R.string.wallets_qr_brightness_bright else R.string.wallets_qr_brightness_normal)
    val name = stringResource(R.string.wallets_qr_brightness)
    // On the white plate: the light palette in both appearances, like the plate itself.
    SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
        val colors = SignerTheme.colors
        val fill = if (bright) colors.accent else colors.card
        val ink = if (bright) colors.onAccent else colors.secondaryLabel
        Box(
            modifier = modifier
                .height(BRIGHTNESS_TARGET)
                .testTag(QR_BRIGHTNESS_TAG)
                .toggleable(
                    value = bright,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Switch,
                    onValueChange = {
                        haptics.selection()
                        onBrightChange(it)
                    },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = name
                    stateDescription = label
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .pressScale(interaction, scale = 0.94f, alpha = 1f)
                    .height(26.dp)
                    .clip(SignerShapes.capsule)
                    .background(fill)
                    .then(if (bright) Modifier else Modifier.border(0.5.dp, colors.separator, SignerShapes.capsule))
                    .padding(start = 8.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (bright) Icons.Rounded.LightMode else Icons.Outlined.LightMode,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(15.dp),
                )
                Text(label, style = SignerTheme.typography.caption2.semibold(), color = ink, maxLines = 1)
            }
        }
    }
}

private val BRIGHTNESS_TARGET = 48.dp

/** The corner radius of `QrCodeView`'s plate (`:core:scanner`, design-system §5.12: `RoundedCornerShape(24.dp)`). */
internal val QR_PLATE_CORNER_RADIUS = 24.dp

/** Android-only test tag of the brightness switch (iOS has no such control). */
internal const val QR_BRIGHTNESS_TAG = "qr.brightness"

/**
 * [value] (a protocol line such as `ur:crypto-account · CBOR · 170 bytes`) kept left to right inside a right-to-left
 * interface: wrapped in a left-to-right isolate (U+2066 … U+2069) so its tokens are not reordered; unchanged otherwise.
 */
@Composable
internal fun leftToRight(value: String): String {
    if (LocalLayoutDirection.current != LayoutDirection.Rtl) return value
    return buildString {
        append(LEFT_TO_RIGHT_ISOLATE)
        append(value)
        append(POP_DIRECTIONAL_ISOLATE)
    }
}

private val LEFT_TO_RIGHT_ISOLATE = Char(0x2066)
private val POP_DIRECTIONAL_ISOLATE = Char(0x2069)
