package com.bittool.signer.feature.setup.backup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.GroupedSection
import com.bittool.signer.core.designsystem.component.IconRowDividerInset
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.setup.ui.SheetTitleBar
import com.bittool.signer.feature.setup.ui.iconSizeFor
import com.bittool.signer.core.l10n.R as L10n

/** "Security Tips" (iOS `SecurityTipsButton`, `RecoveryBackupViews.swift:344-354`): full width, secondary, `backup.tips`. */
@Composable
internal fun SecurityTipsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val style = SignerTheme.typography.subheadline
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = SignerMetrics.minTouchTarget).testTag("backup.tips"),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.secondaryLabel),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(iconSizeFor(style, 1.2f)))
            Text(stringResource(L10n.string.security_tips), style = style)
        }
    }
}

/** One safety tip; [id] is the iOS SF Symbol name, the stable identity of the row. */
@Immutable
internal data class SecurityTip(val id: String, val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val detail: Int)

/** The tips in iOS order (`RecoveryBackupViews.swift:371-388`; setup-backup §8.7). */
internal val SecurityTips: List<SecurityTip> by lazy {
    listOf(
        SecurityTip("pencil.and.list.clipboard", SignerIcons.Checklist, L10n.string.write_it_on_paper, L10n.string.copy_every_word_by_hand_in_order_keep_the_paper),
        SecurityTip("eye.slash", SignerIcons.VisibilityOff, L10n.string.keep_it_offline, L10n.string.never_take_a_screenshot_or_photo_of_the_words_and_never),
        SecurityTip("qrcode", SignerIcons.QrCode, L10n.string.the_qr_code_is_the_phrase, L10n.string.anyone_who_scans_or_photographs_it_can_take_your_funds),
        SecurityTip("rectangle.split.3x1", SignerIcons.ViewWeek, L10n.string.recovery_cards_any_two_restore, L10n.string.keep_the_three_cards_in_three_different_places_one_card),
        SecurityTip("person.2.slash", SignerIcons.GroupOff, L10n.string.never_share_it, L10n.string.anyone_with_these_words_can_take_your_funds_from_any),
        SecurityTip("exclamationmark.shield", SignerIcons.ShieldAlert, L10n.string.never_type_it_into_a_website, L10n.string.only_enter_your_recovery_phrase_into_a_wallet_you_trust),
        SecurityTip("key", SignerIcons.Key, L10n.string.back_up_any_passphrase_separately, L10n.string.if_this_wallet_uses_a_bip39_passphrase_the_words_alone),
        SecurityTip("lock", SignerIcons.Lock, L10n.string.your_password_is_not_a_backup, L10n.string.the_wallet_password_only_unlocks_the_copy_on_this),
    )
}

/**
 * The tips sheet (iOS `SecurityTipsSheet`, `RecoveryBackupViews.swift:357-415`): an inset-grouped list of the eight
 * tips (accent glyph, headline title, subheadline detail), "Done", medium and large detents (partially expanded first).
 * It shows no recovery material.
 */
@Composable
internal fun SecurityTipsSheet(onDismiss: () -> Unit) {
    SignerBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = false, testTag = "tips.sheet") {
        val sheet = this
        Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }) {
            SheetTitleBar(title = stringResource(L10n.string.security_tips), onDone = { sheet.dismiss() }, doneTag = "tips.done")
            SecurityTipsList()
        }
    }
}

@Composable
internal fun SecurityTipsList(modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 16.dp)) {
        GroupedSection {
            for (tip in SecurityTips) {
                item(key = tip.id, dividerInset = IconRowDividerInset) { TipRow(tip) }
            }
        }
        Spacer(Modifier.height(SignerMetrics.listBottomInset))
    }
}

@Composable
private fun TipRow(tip: SecurityTip) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(tip.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(iconSizeFor(typography.headline, 1.41f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(tip.title), style = typography.headline, color = colors.label)
            Text(stringResource(tip.detail), style = typography.subheadline, color = colors.secondaryLabel)
        }
    }
}
