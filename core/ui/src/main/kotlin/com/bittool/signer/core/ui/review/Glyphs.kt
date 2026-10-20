package com.bittool.signer.core.ui.review

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.registry.ContractCategory
import com.bittool.signer.core.model.review.ReviewAssetKind

/**
 * The glyph of an NFT-aware review (iOS `ReviewAssetKind.systemImage`, `Models/Models.swift:192-198`): a framed picture
 * for an NFT and the ambiguous NFT-or-token case, a neutral stack for ERC-1155 (IDs may be fungible, never called an
 * NFT), a tag for marketplace orders.
 */
val ReviewAssetKind.glyph: ImageVector
    get() = when (this) {
        ReviewAssetKind.Nft, ReviewAssetKind.NftOrToken -> SignerIcons.PhotoFrame
        ReviewAssetKind.MultiToken -> SignerIcons.Stack
        ReviewAssetKind.NftOrder -> SignerIcons.Tag
    }

/** What TalkBack reads in place of the glyph (iOS `ReviewAssetKind.accessibilityLabel`). */
@get:StringRes
val ReviewAssetKind.accessibilityLabel: Int
    get() = when (this) {
        ReviewAssetKind.Nft -> R.string.nft
        ReviewAssetKind.NftOrToken -> R.string.nft_or_token
        ReviewAssetKind.MultiToken -> R.string.erc_1155_token
        ReviewAssetKind.NftOrder -> R.string.nft_order
    }

/**
 * The offline NFT mark (iOS `NFTGlyph`, `Features/Sign/RequestReviewView.swift:662-690`; scan-sign §11.4): artwork is
 * never fetched. A rounded square (radius 26 %) of the accent at 14 % with the [kind] glyph at 44 % in the accent; the
 * ambiguous NFT-or-token case adds a warning-coloured question-mark badge at the bottom trailing corner, ringed in the
 * card colour. The rounded square sets it apart from circular token and network logos. One labelled element.
 */
@Composable
fun NftGlyph(kind: ReviewAssetKind, modifier: Modifier = Modifier, size: Dp = 60.dp) {
    val colors = SignerTheme.colors
    val label = stringResource(kind.accessibilityLabel)
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = label }) {
        Box(
            Modifier.size(size).background(colors.accent.copy(alpha = 0.14f), RoundedCornerShape(percent = 26)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kind.glyph, contentDescription = null, tint = colors.accent, modifier = Modifier.size(size * 0.44f))
        }
        if (kind == ReviewAssetKind.NftOrToken) {
            val badge = size * 0.38f
            val ring = maxOf(1.5.dp, size * 0.04f)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = size * 0.12f, y = size * 0.12f)
                    .background(colors.card, CircleShape)
                    .padding(ring)
                    .size(badge)
                    .background(colors.warning, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SignerIcons.QuestionMark, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.24f))
            }
        }
    }
}

/**
 * The glyph of a known-contract category (iOS `KnownContractEntry.systemImage`, `Features/Settings/TokenDirectoryView.swift:67-81`):
 * every category its own (KnownContractTests `everyCategoryHasItsOwnGlyph`); a category added by a later import draws
 * the neutral grid.
 */
fun contractCategoryIcon(category: ContractCategory): ImageVector = when (category) {
    ContractCategory.Aggregator -> SignerIcons.Merge
    ContractCategory.Bridge -> SignerIcons.SwapHoriz
    ContractCategory.Dex -> SignerIcons.Cycle
    ContractCategory.Infra -> SignerIcons.Settings
    ContractCategory.Lending -> SignerIcons.Columns
    ContractCategory.Nft -> SignerIcons.PhotoFrame
    ContractCategory.Perp -> SignerIcons.Chart
    ContractCategory.Staking -> SignerIcons.ShieldLock
    ContractCategory.Token -> SignerIcons.Token
    ContractCategory.Wallet -> SignerIcons.WalletPass
    ContractCategory.Other -> SignerIcons.GridView
}

/**
 * A known contract's category mark as Settings › Networks lists it (iOS `ContractRow`, `TokenDirectoryView.swift:337-342`):
 * the category glyph in the accent on a 36 dp accent-tinted (12 %) disc. Decorative.
 */
@Composable
fun ContractCategoryGlyph(category: ContractCategory, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val colors = SignerTheme.colors
    Box(
        modifier.size(size).background(colors.accent.copy(alpha = 0.12f), CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(contractCategoryIcon(category), contentDescription = null, tint = colors.accent, modifier = Modifier.size(size * 0.56f))
    }
}
