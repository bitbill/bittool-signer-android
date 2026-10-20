package com.bittool.signer.feature.settings.compatibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.IconRowDividerInset
import com.bittool.signer.core.designsystem.component.NavRow
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.CompatibleWallet
import com.bittool.signer.core.ui.address.CopyableText
import com.bittool.signer.core.ui.wallet.WalletAppIcon
import com.bittool.signer.feature.settings.SectionFrame
import com.bittool.signer.feature.settings.SectionGap
import com.bittool.signer.feature.settings.listContentPadding

/** Test tags of the directory (iOS `directory.wallet.<name>`, `directory.submit`). */
object DirectoryTags {
    const val SUBMIT = "directory.submit"
    const val LIST = "directory.list"

    fun wallet(name: String) = "directory.wallet.$name"
}

/**
 * The Wallets segment (iOS `WalletDirectoryView`, activity-settings.md §6.2): the two notices that are the point of
 * the page, every wallet with its support and its site as text to copy (the app opens no browser), and "Submit a
 * Wallet".
 */
@Composable
internal fun WalletDirectory(wallets: List<CompatibleWallet>, insets: PaddingValues, onSubmitWallet: () -> Unit) {
    val copyLabel = stringResource(R.string.copy_address_2)
    val submit = stringResource(R.string.submit_a_wallet)
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().testTag(DirectoryTags.LIST),
        contentPadding = listContentPadding(insets),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        item(key = "notices") {
            Column(SectionFrame, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Callout(stringResource(R.string.every_wallet_here_supports_or_says_it_supports_bittool), tone = Tone.Warning)
                Callout(stringResource(R.string.this_page_is_information_only_check_any_wallet_yourself), tone = Tone.Info)
            }
        }
        item(key = "wallets") {
            GroupedSection(
                modifier = SectionFrame,
                header = stringResource(R.string.x_wallets, wallets.size),
                footer = stringResource(R.string.the_app_never_opens_a_browser_copy_the_address_and_open),
            ) {
                wallets.forEach { wallet ->
                    item(key = wallet.name) { DirectoryRow(wallet, copyLabel) }
                }
            }
        }
        item(key = "submit") {
            GroupedSection(SectionFrame) {
                item(key = "submit", dividerInset = IconRowDividerInset) {
                    NavRow(submit, SignerIcons.Mail, onSubmitWallet, modifier = Modifier.testTag(DirectoryTags.SUBMIT))
                }
            }
        }
    }
}

/** iOS `WalletDirectoryView.row`: logo, name, platforms, the support badge, then the site with its copy button. */
@Composable
private fun DirectoryRow(wallet: CompatibleWallet, copyLabel: String) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)
            .testTag(DirectoryTags.wallet(wallet.name)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletAppIcon(wallet.asset, size = 34.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(wallet.name, style = typography.subheadline.semibold(), color = colors.label)
                Text(wallet.platforms, style = typography.caption, color = colors.secondaryLabel)
            }
            Box(Modifier.weight(0.8f), contentAlignment = Alignment.CenterEnd) { SupportBadge(wallet.support) }
        }
        CopyableText(text = wallet.site, copyLabel = copyLabel)
    }
}

/** iOS `SupportBadge`: "Watch and sign by QR" in green or "Watch only" in secondary, caption2 semibold, wrapping. */
@Composable
private fun SupportBadge(support: CompatibleWallet.Support, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val signing = support == CompatibleWallet.Support.Signing
    val tint = if (signing) colors.success else colors.secondaryLabel
    val textColor = if (signing) colors.successOnTint else colors.secondaryLabel
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (signing) SignerIcons.QrCodeScanner else SignerIcons.Visibility,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = stringResource(if (signing) R.string.watch_and_sign_by_qr else R.string.watch_only),
            style = SignerTheme.typography.caption2.semibold(),
            color = textColor,
            textAlign = TextAlign.End,
        )
    }
}
