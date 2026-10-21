package com.bittool.signer.feature.sign.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.l10n.reviewText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.signing.MatchingExplanation
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.ui.text.asString

/**
 * The message of a matching issue (`signing.matchError`, iOS `Label(ReviewText.phrase(store.matchingError))`, iOS
 * ff2756f): the host's own messages from their resources (ARCHITECTURE §4.3, gaps.md G6), and the resolver's messages
 * ("No matching wallet", "Missing signer information", "Ambiguous wallet match", "Wallet setup required", "Invalid
 * signing request") and any other engine English through `ReviewText.phrase`: translated when the iOS catalogue has
 * the exact phrase, else verbatim. A thrown error shows its own text, an engine error's English also through the
 * phrase lookup (iOS passes `error.localizedDescription` through it too).
 */
@Composable
@ReadOnlyComposable
internal fun MatchingIssue.message(): String = when (this) {
    MatchingIssue.Unsupported -> stringResource(R.string.sign_unsupported_signing_protocol)
    MatchingIssue.Interrupted -> stringResource(R.string.sign_matching_interrupted)
    MatchingIssue.WalletsChanged -> stringResource(R.string.wallet_information_changed_match_this_request_again)
    is MatchingIssue.Engine -> enginePhrase(message)
    is MatchingIssue.Failure -> (text as? UiText.Verbatim)?.let { enginePhrase(it.text) } ?: text.asString()
}

/** iOS `ReviewText.phrase` in the composition's language: the catalogue translation of [english], else [english]. */
@Composable
@ReadOnlyComposable
private fun enginePhrase(english: String): String = LocalResources.current.reviewText().phrase(english)

/** The explanation under a matching issue (scan-sign.md §11.7 table; localized on iOS). */
@get:StringRes
internal val MatchingExplanation.res: Int
    get() = when (this) {
        MatchingExplanation.NoMatch -> R.string.this_request_does_not_belong_to_any_wallet_on_this
        MatchingExplanation.MissingSigner -> R.string.the_request_does_not_say_which_key_should_sign_export
        MatchingExplanation.Ambiguous -> R.string.more_than_one_saved_wallet_matches_delete_the_duplicate
        MatchingExplanation.SetupRequired -> R.string.a_wallet_needs_repair_before_it_can_be_matched_open_it
        MatchingExplanation.Unsupported -> R.string.bittool_signer_can_show_this_request_but_cannot_sign
        MatchingExplanation.Default -> R.string.this_request_cannot_be_signed_nothing_has_been_signed
    }
