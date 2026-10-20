package com.bittool.signer.feature.settings.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.component.ValueRow
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.ui.address.CopyableText
import com.bittool.signer.feature.settings.RowContentPadding
import com.bittool.signer.feature.settings.SectionFrame
import com.bittool.signer.feature.settings.SectionGap
import com.bittool.signer.feature.settings.SettingsPage
import com.bittool.signer.feature.settings.TitledText
import com.bittool.signer.feature.settings.listContentPadding
import com.bittool.signer.feature.settings.rememberResumed

/** Test tags of Contact Us (iOS identifiers). */
object ContactTags {
    const val PUBLISHER = "contact.publisher"
    const val EMAIL = "contact.email"
}

/**
 * Contact Us — the operating entity (iOS `ContactView`, activity-settings.md §8, inventory row 71): the publisher and
 * the support address as text to copy. The app opens no mail app.
 */
@Composable
fun ContactScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val back = rememberResumed(onBack)
    val publisherLabel = stringResource(R.string.publisher)
    val publisher = stringResource(R.string.settings_publisher_name)
    val email = stringResource(R.string.settings_support_email)
    val copyLabel = stringResource(R.string.copy_address_2)
    SettingsPage(title = stringResource(R.string.contact_us), onBack = back, modifier = modifier) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()),
            contentPadding = listContentPadding(insets),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            item(key = "publisher") {
                GroupedSection(SectionFrame) {
                    item { ValueRow(publisherLabel, publisher, Modifier.testTag(ContactTags.PUBLISHER), verbatim = true) }
                }
            }
            item(key = "email") {
                GroupedSection(
                    modifier = SectionFrame,
                    header = stringResource(R.string.contact_email),
                    footer = stringResource(R.string.the_app_never_opens_a_mail_app_copy_the_address_and),
                ) {
                    item {
                        CopyableText(
                            text = email,
                            copyLabel = copyLabel,
                            style = SignerTheme.typography.callout,
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag(ContactTags.EMAIL),
                        )
                    }
                }
            }
        }
    }
}

/** Test tags of Submit a Wallet. */
object SubmitWalletTags {
    const val LIST = "submitWallet.list"
}

/**
 * Submit a Wallet (iOS `SubmitWalletView`, activity-settings.md §7, inventory row 44): how to put a wallet on the
 * compatibility list — the address and the subject to copy (the subject stays English: it is the e-mail's subject),
 * what to include, and that a listing is not an endorsement. No mail intent, no browser. Root-level destination,
 * reached from Connect and from Compatibility.
 */
@Composable
fun SubmitWalletScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val back = rememberResumed(onBack)
    val copyLabel = stringResource(R.string.copy_address_2)
    val intro = stringResource(R.string.does_your_wallet_read_bittool_signers_qr_codes_send_us)
    val to = stringResource(R.string.to)
    val subject = stringResource(R.string.subject)
    val address = stringResource(R.string.settings_support_email)
    val subjectValue = stringResource(R.string.settings_submit_subject)
    val items = listOf(
        "name" to (R.string.wallet_name to R.string.the_name_as_it_is_published),
        "site" to (R.string.official_website to R.string.the_address_the_wallet_itself_publishes),
        "icon" to (R.string.app_icon to R.string.a_link_to_the_image_or_attach_it_a_square_icon_of_at),
        "source" to (R.string.source_code to R.string.a_link_to_the_repository_if_the_wallet_is_open_source),
        "formats" to (R.string.qr_formats_supported to R.string.which_of_these_the_wallet_reads_wallet_level_and_coin),
    ).map { (id, texts) -> Triple(id, stringResource(texts.first), stringResource(texts.second)) }
    SettingsPage(title = stringResource(R.string.submit_a_wallet), onBack = back, modifier = modifier) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()).testTag(SubmitWalletTags.LIST),
            contentPadding = listContentPadding(insets),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            item(key = "intro") {
                GroupedSection(SectionFrame) {
                    item {
                        Text(
                            text = intro,
                            style = SignerTheme.typography.subheadline,
                            color = SignerTheme.colors.label,
                            modifier = Modifier.fillMaxWidth().padding(RowContentPadding),
                        )
                    }
                }
            }
            item(key = "email") {
                GroupedSection(modifier = SectionFrame, header = stringResource(R.string.email)) {
                    item(key = "to") { LabelledCopy(to, address, copyLabel) }
                    item(key = "subject") { LabelledCopy(subject, subjectValue, copyLabel) }
                }
            }
            item(key = "include") {
                GroupedSection(
                    modifier = SectionFrame,
                    header = stringResource(R.string.include_at_least),
                    footer = stringResource(R.string.the_app_never_opens_a_browser_copy_the_address_and_open),
                ) {
                    items.forEach { (id, title, detail) -> item(key = id) { TitledText(title, detail) } }
                }
            }
            item(key = "endorsement") {
                Callout(stringResource(R.string.being_listed_is_not_a_review_or_an_endorsement_we_do), SectionFrame, tone = Tone.Info)
            }
        }
    }
}

/** A caption over a copyable value (iOS `SubmitWalletView.labelled`). */
@Composable
private fun LabelledCopy(title: String, value: String, copyLabel: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(title, style = SignerTheme.typography.caption.semibold(), color = SignerTheme.colors.secondaryLabel)
        CopyableText(text = value, copyLabel = copyLabel, style = SignerTheme.typography.callout)
    }
}
