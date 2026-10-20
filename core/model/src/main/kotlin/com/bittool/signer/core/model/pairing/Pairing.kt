package com.bittool.signer.core.model.pairing

import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.json.long
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.prettyJson
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.json.strings
import com.bittool.signer.core.model.util.utf8Size
import java.text.NumberFormat
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * Developer view of exactly what a QR code carries, decoded back from its own frames by the engine (models-state.md
 * §3.4, iOS `Models.swift:595-650`). Null (disclosure hidden) unless `payloadFormat` is json/hex/text with a usable
 * payload.
 */
data class QrPayloadView(
    val format: Format,
    /** Pretty, key-sorted JSON (Apple style) or the hex/text payload. */
    val text: String,
    val byteCount: Long,
    /** Engine `type` (`crypto-account`, `eth-signature`, `bbqr-psbt`, `address`, …). */
    val qrType: String?,
    /** BBQr encoding character (`H`, `2`, `Z`) of the first frame, when it is a BBQr frame. */
    val bbqrEncoding: String?,
) {
    enum class Format(val wire: String) { Json("json"), Hex("hex"), Text("text") }

    /** "Payload (JSON)" (an iOS key) / "Payload (Hex)" / "Payload (Text)" (looked up but untranslated on iOS). */
    val titleEnglish: String
        get() = when (format) {
            Format.Json -> "Payload (JSON)"
            Format.Hex -> "Payload (Hex)"
            Format.Text -> "Payload (Text)"
        }

    /**
     * The transport line, verbatim English on iOS ("ur:crypto-account · CBOR · 170 bytes"). The byte count is grouped
     * in [locale] (iOS `Int.formatted()`); pass the app locale.
     */
    fun summary(locale: Locale = Locale.ROOT): String {
        val bytes = "${NumberFormat.getIntegerInstance(locale).format(byteCount)} bytes"
        return when (qrType) {
            "bbqr-psbt" -> "BBQr · PSBT (${if (bbqrEncoding == "H") "hex" else "Base32"}) · $bytes"
            "psbt-base64" -> "Plain text · PSBT Base64 · $bytes"
            "psbt-hex" -> "Plain text · PSBT hex · $bytes"
            null, "address" -> "Plain text · $bytes"
            else -> "ur:$qrType · CBOR · $bytes"
        }
    }

    /** Whether the payload is a PSBT (the result page adds the psbt_base64 note). */
    val isPsbt: Boolean get() = qrType in PSBT_TYPES

    companion object {
        private val PSBT_TYPES = setOf("crypto-psbt", "psbt", "bbqr-psbt", "psbt-base64", "psbt-hex")

        fun parse(raw: JsonObject): QrPayloadView? {
            val format = Format.entries.firstOrNull { it.wire == raw.str("payloadFormat") } ?: return null
            val text = when (format) {
                Format.Json -> raw.obj("payload")?.prettyJson() ?: return null
                Format.Hex, Format.Text -> raw.str("payload")?.takeIf { it.isNotEmpty() } ?: return null
            }
            val first = raw.strings("frames")?.firstOrNull() ?: ""
            return QrPayloadView(
                format = format,
                text = text,
                byteCount = raw.long("payloadBytes") ?: text.utf8Size().toLong(),
                qrType = raw.str("type"),
                bbqrEncoding = if (first.startsWith("B$") && first.length > 2) first.substring(2, 3) else null,
            )
        }

        /**
         * The Connect payload note (verbatim English on iOS, `ConnectView.swift:302-306`): only for JSON payloads whose
         * text contains the fingerprint's decimal value, "Fingerprints are stored as 32-bit numbers: 1942346250 is
         * fingerprint 73C5DA0A.".
         */
        fun fingerprintNotes(view: QrPayloadView?, masterFingerprint: String?): List<String> {
            if (view == null || view.format != Format.Json || masterFingerprint == null) return emptyList()
            val value = masterFingerprint.toLongOrNull(16) ?: return emptyList()
            val decimal = value.toString()
            if (!view.text.contains(decimal)) return emptyList()
            return listOf("Fingerprints are stored as 32-bit numbers: $decimal is fingerprint ${masterFingerprint.uppercase(Locale.ROOT)}.")
        }

        /** The result-page note for PSBT payloads (verbatim English on iOS). */
        const val PSBT_NOTE = "psbt_base64 is the exact PSBT this QR code carries; decoded is read from it for reference."
    }
}

/**
 * The Coin Level export from `createPublicPairing` (models-state.md §3.4, iOS `Models.swift:571-589`).
 * Throws "Unable to encode public wallet information." without frames.
 */
data class PairingPayload(
    val frames: List<String>,
    /** What "Copy QR Data" copies: the result's `text`, else frames joined by "\n". */
    val text: String,
    /** First receive address; empty for multisig cosigners (the UI hides it). */
    val address: String,
    /** Account origin (xpub modes) or leaf path (address mode, Solana, TRON). */
    val path: String,
    val outputMode: String,
    /** Engine English note; parsed but never displayed on iOS. */
    val note: String?,
    val payloadView: QrPayloadView?,
) {
    companion object {
        fun parse(raw: JsonObject): PairingPayload {
            val frames = raw.strings("frames") ?: emptyList()
            if (frames.isEmpty()) throw AppIssue("Unable to encode public wallet information.")
            return PairingPayload(
                frames = frames,
                text = raw.str("text") ?: frames.joinToString("\n"),
                address = raw.str("address") ?: "",
                path = raw.str("path") ?: "",
                outputMode = raw.str("outputMode") ?: "",
                note = raw.str("note"),
                payloadView = QrPayloadView.parse(raw),
            )
        }
    }
}

/**
 * The wallet-level export (`bittool-signer-wallet` v1, `ur:bytes`, ≥ 2 frames) from `createWalletPairing`
 * (models-state.md §3.4, iOS `Models.swift:535-569`). Throws "Unable to encode the wallet pairing." without frames or
 * without an object `payload`.
 */
data class WalletPairingPayload(
    val frames: List<String>,
    val text: String,
    val mode: String,
    /** Canonical CBOR length (shown raw, ungrouped, in the Format line). */
    val payloadBytes: Long,
    /** The 9 first-address rows, engine order (btc ×4, evm, tron, sol ×3). */
    val firstAddresses: List<Line>,
    /** `payload` pretty-printed, key-sorted, slashes unescaped (developer view). */
    val json: String,
) {
    /** One verification row; [id] includes the profile because a chain contributes several rows. */
    data class Line(val chain: String, val profile: String, val address: String, val path: String) {
        val id: String get() = "$chain/$profile"
    }

    /** The Format line (verbatim English): `bittool-signer-wallet v1 · ur:bytes (canonical CBOR) · 1561 bytes`. */
    val formatLine: String get() = "bittool-signer-wallet v1 · ur:bytes (canonical CBOR) · $payloadBytes bytes"

    companion object {
        fun parse(raw: JsonObject): WalletPairingPayload {
            val frames = raw.strings("frames") ?: emptyList()
            val payload = raw.obj("payload")
            if (frames.isEmpty() || payload == null) throw AppIssue("Unable to encode the wallet pairing.")
            val lines = (raw.objects("verify") ?: emptyList()).mapNotNull { item ->
                val chain = item.str("chain") ?: return@mapNotNull null
                val address = item.str("address") ?: return@mapNotNull null
                val path = item.str("path") ?: return@mapNotNull null
                Line(chain, item.str("profile") ?: "standard", address, path)
            }
            return WalletPairingPayload(
                frames = frames,
                text = raw.str("text") ?: frames.joinToString("\n"),
                mode = raw.str("mode") ?: "",
                payloadBytes = raw.long("payloadBytes") ?: 0,
                firstAddresses = lines,
                json = payload.prettyJson(),
            )
        }
    }
}
