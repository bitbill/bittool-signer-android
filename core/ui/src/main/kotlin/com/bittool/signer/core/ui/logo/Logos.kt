package com.bittool.signer.core.ui.logo

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.designsystem.R as DesignR
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.TokenIconSource
import com.bittool.signer.core.model.util.Graphemes
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bundled vector logo of [this] network (converted from the iOS SVGs; a `when`, never `getIdentifier`). */
@get:DrawableRes
val NetworkBrand.logoRes: Int
    get() = when (this) {
        NetworkBrand.Bitcoin -> DesignR.drawable.ic_chain_bitcoin
        NetworkBrand.Ethereum -> DesignR.drawable.ic_chain_ethereum
        NetworkBrand.Bnb -> DesignR.drawable.ic_chain_bnb
        NetworkBrand.Arbitrum -> DesignR.drawable.ic_chain_arbitrum
        NetworkBrand.Base -> DesignR.drawable.ic_chain_base
        NetworkBrand.Tron -> DesignR.drawable.ic_chain_tron
        NetworkBrand.Solana -> DesignR.drawable.ic_chain_solana
    }

/**
 * A network's own logo (iOS `NetworkLogo`, `Design/Logos.swift:83-96`): the bundled artwork with a circular hairline
 * (label at 14 %, `max(0.5, size / 56)` wide), labelled with the network name (verbatim). Never loaded remotely.
 */
@Composable
fun NetworkLogo(network: NetworkBrand, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val name = network.displayName
    Image(
        painter = painterResource(network.logoRes),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .border(maxOf(0.5.dp, size / 56f), SignerTheme.colors.label.copy(alpha = 0.14f), CircleShape)
            .semantics { contentDescription = name },
    )
}

/** A chain family's logo: its primary network's (EVM → Ethereum) (iOS `ChainLogo`). */
@Composable
fun ChainLogo(chain: Chain, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    NetworkLogo(chain.primaryNetwork, modifier, size)
}

/**
 * A chain family's mark (iOS `ChainGlyph`, `Design/Logos.swift:104-123`): EVM shows its four headline networks as a
 * 2 × 2 cluster (Ethereum, BNB / Arbitrum, Base) of `size × 0.5 − 1`, read as "EVM networks"; other chains their logo.
 */
@Composable
fun ChainGlyph(chain: Chain, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    if (chain != Chain.Evm) {
        ChainLogo(chain, modifier, size)
        return
    }
    val item = size * 0.5f - 1.dp
    val label = stringResource(R.string.evm_networks)
    Column(
        modifier = modifier.size(size).clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NetworkBrand.evmNetworks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { row.forEach { NetworkLogo(it, size = item) } }
        }
    }
}

/**
 * The neutral badge of a reviewed network without bundled artwork (iOS `GenericNetworkLogo`): a `gray5` disc with the
 * connected-points glyph at 46 % in secondary. Hidden from accessibility (the network name is shown next to it).
 */
@Composable
fun GenericNetworkLogo(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier = modifier.size(size).background(SignerTheme.colors.gray5, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(SignerIcons.Hub, contentDescription = null, tint = SignerTheme.colors.secondaryLabel, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * Overlapping network logos (iOS `NetworkLogoStack`, `Design/Logos.swift:140-157`): each logo on a [ring]-coloured
 * disc padded `max(1.5, size × 0.08)`, later logos over earlier ones, overlapping by `size × 0.28`; mirrored in RTL like
 * the iOS stack. Read as one element: the short names joined by ", ".
 */
@Composable
fun NetworkLogoStack(
    modifier: Modifier = Modifier,
    networks: List<NetworkBrand> = NetworkBrand.entries,
    size: Dp = 22.dp,
    ring: Color = SignerTheme.colors.card,
) {
    val label = remember(networks) { networks.joinToString(", ") { it.shortName } }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(-(size * 0.28f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        networks.forEach { network ->
            Box(Modifier.background(ring, CircleShape).padding(maxOf(1.5.dp, size * 0.08f))) {
                NetworkLogo(network, size = size)
            }
        }
    }
}

/** The token-icon pack the app reads (`:app` provides the Hilt `TokenIconSource`); empty by default (letters only). */
val LocalTokenIconSource: ProvidableCompositionLocal<TokenIconSource> = staticCompositionLocalOf { TokenIconSource.Empty }

/**
 * Bundled token artwork by registry content hash (iOS `TokenIcon`, `Design/Logos.swift:159-185`): the pack's PNG,
 * decoded off the main thread and cached, scaled to fill a circle with a hairline (label at 8 %); unknown or missing
 * artwork shows a `gray5` disc with the first three letters of [symbol] upper-cased in bold (`size × 0.3`, shrinking to
 * half). Labelled with [symbol]. Never touches the network.
 */
@Composable
fun TokenIcon(iconId: String?, symbol: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val source = LocalTokenIconSource.current
    val image by produceState(initialValue = iconId?.let(TokenIconCache::peek), iconId, source) {
        // A recycled row with a new ID never keeps the previous token's artwork.
        value = iconId?.let(TokenIconCache::peek)
        if (iconId != null && value == null) value = TokenIconCache.load(source, iconId)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(0.5.dp, SignerTheme.colors.label.copy(alpha = 0.08f), CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = symbol },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            TokenLetters(symbol, size)
        }
    }
}

@Composable
private fun TokenLetters(symbol: String, size: Dp) {
    val fontSize = with(LocalDensity.current) { (size * 0.3f).toSp() }
    Box(Modifier.fillMaxSize().background(SignerTheme.colors.gray5).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        BasicText(
            text = tokenLetters(symbol),
            modifier = Modifier.padding(horizontal = size * 0.1f),
            style = SignerTheme.typography.body.copy(
                color = SignerTheme.colors.secondaryLabel,
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = fontSize * 0.5f, maxFontSize = fontSize),
        )
    }
}

/** iOS `String(symbol.prefix(3)).uppercased()` (graphemes, locale-independent). */
internal fun tokenLetters(symbol: String): String = Graphemes.prefix(symbol, 3).uppercase(Locale.ROOT)

/**
 * Decoded token icons: an LRU of 200 bitmaps keyed by content hash (iOS `NSCache` count limit 200), decoding on
 * `Dispatchers.Default`. A failed or missing decode is not cached, so a later attempt can succeed.
 */
internal object TokenIconCache {
    private val cache = LruCache<String, ImageBitmap>(200)

    fun peek(iconId: String): ImageBitmap? = cache.get(iconId)

    suspend fun load(source: TokenIconSource, iconId: String): ImageBitmap? {
        cache.get(iconId)?.let { return it }
        // Cancellation propagates (a recycled row); any other failure means no artwork.
        val png = runCatchingCancellable { source.png(iconId) }.getOrNull() ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }.getOrNull()
        } ?: return null
        cache.put(iconId, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()
}
