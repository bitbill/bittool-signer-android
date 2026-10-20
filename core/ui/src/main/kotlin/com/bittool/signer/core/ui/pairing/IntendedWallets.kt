package com.bittool.signer.core.ui.pairing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.FirstLineIcon
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.CompatibleWallet
import com.bittool.signer.core.ui.wallet.WalletAppIcon

/**
 * The wallets documented to work with the current export (iOS `IntendedWalletsView`, `ConnectView.swift:599-638`;
 * wallets-pairing §3.10): a card with "Works with", the signing wallets ("Watch and sign by QR", or "Multisig
 * coordinators · sign by QR" for a [multisig] export), the watch-only wallets with their caption (TRON has its own
 * wording), the documentation caveat and the "Submit a Wallet" link ([onSubmitWallet]). Hidden when [wallets] is
 * empty. Listing a wallet is not a certification. Tag `connect.wallets`.
 *
 * [chain] selects the TRON watch-only caption; Compatibility passes its own chain, Connect the selected one.
 */
@Composable
fun IntendedWallets(
    wallets: List<CompatibleWallet>,
    modifier: Modifier = Modifier,
    onSubmitWallet: () -> Unit,
    chain: Chain? = null,
    multisig: Boolean = false,
) {
    if (wallets.isEmpty()) return
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val signing = wallets.filter { it.support == CompatibleWallet.Support.Signing }
    val watching = wallets.filter { it.support == CompatibleWallet.Support.WatchOnly }
    SignerCard(modifier.testTag("connect.wallets")) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.works_with), style = typography.headline, color = colors.label)
            if (signing.isNotEmpty()) {
                WalletGroup(
                    title = stringResource(if (multisig) R.string.multisig_coordinators_sign_by_qr else R.string.watch_and_sign_by_qr),
                    wallets = signing,
                    icon = SignerIcons.QrCodeScanner,
                )
            }
            if (watching.isNotEmpty()) {
                WalletGroup(title = stringResource(R.string.watch_only), wallets = watching, icon = SignerIcons.Visibility)
                Text(
                    text = stringResource(
                        if (chain == Chain.Tron) R.string.these_wallets_can_show_this_addresss_balance_no else R.string.these_wallets_can_show_this_addresss_balance_but_cannot,
                    ),
                    style = typography.caption,
                    color = colors.secondaryLabel,
                )
            }
            Text(stringResource(R.string.based_on_each_wallets_documentation_or_source_code_or), style = typography.caption, color = colors.secondaryLabel)
            SubmitWalletLink(onSubmitWallet)
        }
    }
}

/**
 * "Submit a Wallet" (iOS `SubmitWalletLink`, `ConnectView.swift:640-652`): the envelope and the label in footnote
 * semibold accent; opens the Submit a Wallet page. Tag `connect.submitWallet`.
 */
@Composable
fun SubmitWalletLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("connect.submitWallet")
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SignerIcons.Mail, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.submit_a_wallet), style = SignerTheme.typography.footnote.semibold(), color = colors.accent)
    }
}

/**
 * A titled grid of wallet logos, names and platforms (iOS `WalletGroup`, `ConnectView.swift:654-681`): the [title] with
 * [icon] (subheadline semibold, secondary), then an adaptive grid — columns at least 140 dp wide (260 dp from extra
 * large text), 10 dp apart, rows 12 dp apart — of cells with the 30 dp logo, the name (subheadline, up to 2 lines,
 * shrinking to 85 %) and the platforms (caption2, secondary). Names and platforms are verbatim. One element per cell.
 */
@Composable
fun WalletGroup(title: String, wallets: List<CompatibleWallet>, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val minColumn = if (SignerTheme.isExtraLargeText) 260.dp else 140.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) FirstLineIcon(icon, colors.secondaryLabel, typography.subheadline.semibold(), size = 16.dp)
            Text(title, style = typography.subheadline.semibold(), color = colors.secondaryLabel)
        }
        AdaptiveGrid(minColumn = minColumn, horizontalGap = 10.dp, verticalGap = 12.dp) {
            wallets.forEach { wallet ->
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WalletAppIcon(wallet.asset, size = 30.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        val nameStyle = typography.subheadline.copy(color = colors.label)
                        BasicText(
                            wallet.name,
                            style = nameStyle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = nameStyle.fontSize * 0.85f, maxFontSize = nameStyle.fontSize),
                        )
                        Text(wallet.platforms, style = typography.caption2, color = colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** SwiftUI `LazyVGrid(.adaptive(minimum:))` for a handful of cells: as many equal columns as fit [minColumn]. */
@Composable
internal fun AdaptiveGrid(minColumn: Dp, horizontalGap: Dp, verticalGap: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = horizontalGap.roundToPx()
        val rowGap = verticalGap.roundToPx()
        val columns = adaptiveColumns(width, minColumn.roundToPx(), gap)
        val cell = ((width - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints(maxWidth = cell)) }
        val rows = placeables.chunked(columns)
        val heights = rows.map { row -> row.maxOf { it.height } }
        val total = heights.sum() + rowGap * (rows.size - 1).coerceAtLeast(0)
        layout(width, total) {
            var y = 0
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, placeable -> placeable.placeRelative(c * (cell + gap), y) }
                y += heights[r] + rowGap
            }
        }
    }
}

/** Number of columns of an adaptive grid: as many [minimum]-wide columns as fit with [gap] between them, at least 1. */
internal fun adaptiveColumns(width: Int, minimum: Int, gap: Int): Int = maxOf(1, (width + gap) / (minimum + gap))
