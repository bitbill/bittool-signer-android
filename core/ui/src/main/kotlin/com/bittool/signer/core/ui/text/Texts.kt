package com.bittool.signer.core.ui.text

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.l10n.resolve
import com.bittool.signer.core.l10n.reviewText
import com.bittool.signer.core.model.AccountProfile
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ExportModeChoice
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.review.ReviewDisplay
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.model.review.TransactionReview
import java.text.NumberFormat
import java.util.Locale

/**
 * Resolves this text in the composition's current per-app locale (ARCHITECTURE §1.11). Strings are resolved at draw
 * time, never in ViewModels: the per-app language can change while a ViewModel lives (DECISIONS §4). Recomposes on a
 * configuration change.
 */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current.resources)
}

/** The [ReviewText] of the current language: draw-time translation of engine English (scan-sign.md §17). */
@Composable
fun rememberReviewText(): ReviewText {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { resources.reviewText() }
}

/** The locale of the current composition's resources (number grouping, iOS `formatted()`). */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()

/** A whole number grouped in [locale] ("30,000"); Arabic uses Latin digits through the `-u-nu-latn` app locale. */
fun formatInteger(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

/**
 * The title of an account preset (Connect / Addresses `ProfileMenu`, iOS `AccountProfile.title`, verbatim English on
 * iOS): translated where the word is generic, product names unchanged; unknown presets fall back to their English.
 */
@Composable
@ReadOnlyComposable
fun AccountProfile.localizedTitle(): String = when (title) {
    "Native SegWit" -> stringResource(R.string.ui_profile_native_segwit)
    "Taproot" -> stringResource(R.string.ui_profile_taproot)
    "Nested SegWit" -> stringResource(R.string.ui_profile_nested_segwit)
    "Legacy" -> stringResource(R.string.ui_profile_legacy)
    "Standard" -> stringResource(R.string.ui_profile_standard)
    "Trust" -> stringResource(R.string.ui_profile_trust)
    else -> title
}

/** The "Export as" segment title ("Account XPUB", "Address", "Wallet"). */
@Composable
@ReadOnlyComposable
fun ExportModeChoice.localizedTitle(): String = when (title) {
    "Account XPUB" -> stringResource(R.string.ui_export_account_xpub)
    "Address" -> stringResource(R.string.ui_export_address)
    "Wallet" -> stringResource(R.string.wallet)
    else -> title
}

/** `Chain.longName` ("Bitcoin", "Ethereum & EVM", "TRON", "Solana"). Network names stay verbatim. */
@Composable
@ReadOnlyComposable
fun Chain.localizedLongName(): String = if (this == Chain.Evm) stringResource(R.string.ui_chain_evm_long) else longName

/** The disclosure title of a QR payload: "Payload (JSON)" (catalogue), "Payload (Hex)", "Payload (Text)". */
@Composable
@ReadOnlyComposable
fun QrPayloadView.localizedTitle(): String = when (format) {
    QrPayloadView.Format.Json -> stringResource(R.string.payload_json)
    QrPayloadView.Format.Hex -> stringResource(R.string.ui_payload_hex)
    QrPayloadView.Format.Text -> stringResource(R.string.ui_payload_text)
}

/**
 * The transport line of a QR payload (iOS `QRPayloadView.summary`, verbatim English there): "Plain text · 170 bytes",
 * "ur:crypto-account · CBOR · 170 bytes", "BBQr · PSBT (hex) · 1,204 bytes". Protocol tokens stay; "bytes" and
 * "Plain text" are translated and the number is grouped in the app locale.
 */
@Composable
@ReadOnlyComposable
fun QrPayloadView.localizedSummary(): String {
    val resources = LocalContext.current.resources
    return payloadSummary(this, resources, currentLocale())
}

internal fun payloadSummary(view: QrPayloadView, resources: Resources, locale: Locale): String {
    val bytes = resources.getString(R.string.ui_payload_bytes, formatInteger(view.byteCount, locale))
    val plain = resources.getString(R.string.ui_payload_plain_text)
    return when (view.qrType) {
        "bbqr-psbt" -> "BBQr · PSBT (${if (view.bbqrEncoding == "H") "hex" else "Base32"}) · $bytes"
        "psbt-base64" -> "$plain · PSBT Base64 · $bytes"
        "psbt-hex" -> "$plain · PSBT hex · $bytes"
        null, "address" -> "$plain · $bytes"
        else -> "ur:${view.qrType} · CBOR · $bytes"
    }
}

/**
 * The Connect payload note for a JSON payload that contains the master fingerprint as a decimal (iOS
 * `ConnectView.swift:302-306`, verbatim there): "Fingerprints are stored as 32-bit numbers: 1942346250 is fingerprint
 * 73C5DA0A." Empty when it does not apply (same rule as `QrPayloadView.fingerprintNotes`).
 */
@Composable
@ReadOnlyComposable
fun fingerprintNotes(view: QrPayloadView?, masterFingerprint: String?): List<String> {
    if (QrPayloadView.fingerprintNotes(view, masterFingerprint).isEmpty() || masterFingerprint == null) return emptyList()
    val decimal = masterFingerprint.toLong(16).toString()
    return listOf(stringResource(R.string.ui_fingerprint_note, decimal, masterFingerprint.uppercase(Locale.ROOT)))
}

/** The summary's action line: the display action or the review title through [reviewText]; the default title translated. */
fun reviewActionLine(review: TransactionReview, reviewText: ReviewText, resources: Resources): String {
    val english = review.display?.action ?: review.title
    if (english == TransactionReview.DEFAULT_TITLE) return resources.getString(R.string.ui_review_request)
    return reviewText.phrase(english)
}

/** The asset-contract caption: the engine's own caption verbatim, or the translated default "NFT contract (not verified)". */
fun assetContractCaption(display: ReviewDisplay, resources: Resources): String =
    display.assetContractLabel ?: resources.getString(R.string.ui_nft_contract_not_verified)

private val moreLine = Regex("^\\+(\\d+) more \\(see Details\\)$")

/** A marketplace-order line: the overflow line "+N more (see Details)" translated, every engine line verbatim. */
fun flowLine(line: String, resources: Resources): String {
    val count = moreLine.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull() ?: return line
    return resources.getString(R.string.ui_more_see_details, count)
}
