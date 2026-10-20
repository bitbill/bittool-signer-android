package com.bittool.signer.feature.sign.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.signing.MatchingExplanation
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.ui.text.asString

/**
 * The message of a matching issue (`signing.matchError`, iOS `Label(store.matchingError)`): the host's and the
 * resolver's known English messages translated (ARCHITECTURE §4.3, gaps.md G6), any other engine message verbatim,
 * and a thrown error's own text.
 */
@Composable
@ReadOnlyComposable
internal fun MatchingIssue.message(): String = when (this) {
    MatchingIssue.Unsupported -> stringResource(R.string.sign_unsupported_signing_protocol)
    MatchingIssue.Interrupted -> stringResource(R.string.sign_matching_interrupted)
    MatchingIssue.WalletsChanged -> stringResource(R.string.wallet_information_changed_match_this_request_again)
    is MatchingIssue.Engine -> engineMessageRes(message)?.let { stringResource(it) } ?: message
    is MatchingIssue.Failure -> text.asString()
}

/** The resolver's status messages (`engine/src/resolver.js:17-18`), which iOS draws in English. */
@StringRes
internal fun engineMessageRes(message: String): Int? = when (message) {
    "No matching wallet" -> R.string.sign_no_matching_wallet
    "Missing signer information" -> R.string.sign_missing_signer_information
    "Ambiguous wallet match" -> R.string.sign_ambiguous_wallet_match
    "Wallet setup required" -> R.string.sign_wallet_setup_required
    else -> null
}

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
