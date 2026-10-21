package com.bittool.signer.core.ui.wallet

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.monospacedRun
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import com.bittool.signer.core.ui.R as UiR
import java.security.MessageDigest
import java.util.Locale

/** The two gradient stops of a wallet's avatar (pure, unit-tested against the Swift formula). */
@Immutable
data class WalletAvatarColors(val start: Color, val end: Color) {
    companion object {
        /**
         * iOS `WalletAvatar.colors` (`Design/Logos.swift:195-201`): `bytes = SHA-256(UTF-8(identity))`,
         * `hue = (b0 << 8 | b1) / 65535`, `shift = 0.08 + b2 / 255 × 0.14`; from HSB(hue, 0.62, 0.78) to
         * HSB((hue + shift) mod 1, 0.70, 0.62). SwiftUI HSB is HSV.
         */
        fun of(identity: String): WalletAvatarColors {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val hue = ((b0 shl 8) or b1).toDouble() / 65_535.0
            val shift = 0.08 + b2.toDouble() / 255.0 * 0.14
            val second = (hue + shift) % 1.0
            return WalletAvatarColors(
                start = Color.hsv((hue * 360.0).toFloat().coerceIn(0f, 360f) % 360f, 0.62f, 0.78f),
                end = Color.hsv((second * 360.0).toFloat().coerceIn(0f, 360f) % 360f, 0.70f, 0.62f),
            )
        }

        /** The avatar letter: the first grapheme of the trimmed name upper-cased (locale-independent), else "W". */
        fun initial(name: String): String =
            Graphemes.first(name.trimWhitespacesAndNewlines())?.uppercase(Locale.ROOT) ?: "W"
    }
}

/**
 * A deterministic identity tile per wallet key (iOS `WalletAvatar`, `Design/Logos.swift:187-220`): a top-leading →
 * bottom-trailing gradient from [WalletAvatarColors.of] in a rounded square (radius 30 %), and the first letter of
 * [name] in white semibold at 44 % of [size] with a faint shadow. [identity] is the engine `walletKeyId` or the wallet
 * UUID. "A visual aid only: signing always binds to the full key identity." Hidden from accessibility; the size is
 * fixed, so the letter does not follow the font scale.
 */
@Composable
fun WalletAvatar(identity: String, name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val colors = remember(identity) { WalletAvatarColors.of(identity) }
    val initial = remember(name) { WalletAvatarColors.initial(name) }
    val density = LocalDensity.current
    val letterSize = with(density) { (size * 0.44f).toSp() }
    val shadowOffset = with(density) { 1.dp.toPx() }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 30))
            .background(Brush.linearGradient(listOf(colors.start, colors.end), start = Offset.Zero, end = Offset.Infinite))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = letterSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = SignerTheme.typography.body.copy(
                lineHeight = letterSize,
                shadow = Shadow(Color.Black.copy(alpha = 0.15f), Offset(0f, shadowOffset), shadowOffset),
            ),
        )
    }
}

/**
 * The wallet a password is about to unlock (iOS `WalletIdentityHeader`, `Design/PasswordViews.swift:219-239`): the
 * avatar, the [name] in headline ("Wallet" when empty) and "Fingerprint %@" in caption monospaced, secondary — the
 * upper-cased [fingerprint], or "Setup required" when the wallet has no public index (isolated in a right-to-left
 * interface only, [isolateInRightToLeft]). One accessibility element.
 * The store-bound part (which name, which identity) stays with the caller (design-system §9.1).
 */
@Composable
fun WalletIdentityHeader(
    name: String,
    fingerprint: String?,
    avatarIdentity: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val shownName = name.ifBlank { stringResource(R.string.wallet) }
    val value = fingerprint?.uppercase(Locale.ROOT) ?: stringResource(R.string.ui_setup_required)
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletAvatar(identity = avatarIdentity, name = name, size = size)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(shownName, style = typography.headline, color = colors.label)
            Text(
                // In a right-to-left interface the value is isolated so it stays left to right inside the sentence;
                // left to right the text stays plain, the same string as the Wallets list ("Fingerprint 73C5DA0A").
                text = fingerprintLine(value),
                style = typography.caption.monospaced(),
                color = colors.secondaryLabel,
            )
        }
    }
}

/**
 * The "Fingerprint %@" line (iOS `Text("Fingerprint \(value)").font(.caption.monospaced())`) for [value], the
 * upper-cased fingerprint or "Setup required": the value isolated in a right-to-left interface only
 * ([isolateInRightToLeft]; left to right the plain "Fingerprint 73C5DA0A" every list shows), with the monospaced family
 * on the value itself ([monospacedRun]), so it keeps that face whatever the sentence around it is drawn in (QA
 * regression-10). Draw it in a `monospaced()` style. Every "Fingerprint %@" line uses it: the identity header, the
 * Wallets card, the wallet page and the review's signing wallet (QA production-07, regression-06).
 */
@Composable
@ReadOnlyComposable
fun fingerprintLine(value: String): AnnotatedString =
    monospacedRun(stringResource(R.string.fingerprint_x, isolateInRightToLeft(value)), value)

/** Wraps [value] in Unicode first-strong isolates (FSI … PDI) so its direction does not leak into the sentence. */
fun isolate(value: String): String = buildString {
    append(FIRST_STRONG_ISOLATE)
    append(value)
    append(POP_DIRECTIONAL_ISOLATE)
}

/**
 * [isolate] in a right-to-left interface, [value] unchanged in a left-to-right one: where the isolates change nothing
 * on screen they are left out, so the text is the same as everywhere else (accessibility, e2e matching; QA wallets-15).
 */
@Composable
@ReadOnlyComposable
fun isolateInRightToLeft(value: String): String =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) isolate(value) else value

private val FIRST_STRONG_ISOLATE = Char(0x2068)
private val POP_DIRECTIONAL_ISOLATE = Char(0x2069)

/**
 * The bundled logo of a compatible wallet (iOS `WalletAppIcon`, `Features/Wallets/ConnectView.swift:683-699`): the
 * 180 px icon for `CompatibleWallet.asset`, clipped to a rounded square (radius 22.5 %) with a hairline; a missing
 * asset shows the neutral app glyph in `quaternaryLabel`. Hidden from accessibility (the wallet name is next to it).
 */
@Composable
fun WalletAppIcon(asset: String?, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val shape = RoundedCornerShape(percent = 23)
    val res = walletIconRes(asset)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(0.5.dp, SignerTheme.colors.label.copy(alpha = 0.08f), shape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) {
            Image(painterResource(res), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Icon(SignerIcons.AppFill, contentDescription = null, tint = SignerTheme.colors.quaternaryLabel, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The drawable of a bundled wallet logo by its iOS asset name (a `when`, never `getIdentifier`), or null. */
@DrawableRes
fun walletIconRes(asset: String?): Int? = when (asset) {
    "wallet_ambire" -> UiR.drawable.wallet_ambire
    "wallet_backpack" -> UiR.drawable.wallet_backpack
    "wallet_bitcoinsafe" -> UiR.drawable.wallet_bitcoinsafe
    "wallet_bittool" -> UiR.drawable.wallet_bittool
    "wallet_bluewallet" -> UiR.drawable.wallet_bluewallet
    "wallet_bull" -> UiR.drawable.wallet_bull
    "wallet_electrum" -> UiR.drawable.wallet_electrum
    "wallet_helium" -> UiR.drawable.wallet_helium
    "wallet_imtoken" -> UiR.drawable.wallet_imtoken
    "wallet_jupiter" -> UiR.drawable.wallet_jupiter
    "wallet_keeper" -> UiR.drawable.wallet_keeper
    "wallet_metamask" -> UiR.drawable.wallet_metamask
    "wallet_nunchuk" -> UiR.drawable.wallet_nunchuk
    "wallet_okx" -> UiR.drawable.wallet_okx
    "wallet_onekey" -> UiR.drawable.wallet_onekey
    "wallet_phantom" -> UiR.drawable.wallet_phantom
    "wallet_rabby" -> UiR.drawable.wallet_rabby
    "wallet_solflare" -> UiR.drawable.wallet_solflare
    "wallet_sparrow" -> UiR.drawable.wallet_sparrow
    "wallet_specter" -> UiR.drawable.wallet_specter
    "wallet_tokenpocket" -> UiR.drawable.wallet_tokenpocket
    "wallet_tronlink" -> UiR.drawable.wallet_tronlink
    "wallet_trust" -> UiR.drawable.wallet_trust
    "wallet_zeus" -> UiR.drawable.wallet_zeus
    else -> null
}
