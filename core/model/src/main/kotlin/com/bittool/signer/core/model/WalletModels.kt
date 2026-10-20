package com.bittool.signer.core.model

import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.int
import com.bittool.signer.core.model.json.long
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import com.bittool.signer.core.model.util.utf8Size
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What `WalletVault.list()` returns (models-state.md §2.1): the vault UUID, the name and the creation time. In
 * `SignerState.wallets` [name] is already replaced by the public sidecar's display name when there is one.
 * [createdAtMillis] is epoch milliseconds (millisecond precision keeps creation order stable).
 */
data class VaultMetadata(val id: WalletId, val name: String, val createdAtMillis: Long) {
    companion object {
        /** Wallet names: trimmed (whitespaces and newlines) and 1–40 grapheme clusters (vault-crypto.md §12.1). */
        const val MAX_NAME_LENGTH = 40

        /** The trimmed name when it is valid, else null. */
        fun validName(name: String): String? =
            name.trimWhitespacesAndNewlines().takeIf { it.isNotEmpty() && !Graphemes.exceeds(it, MAX_NAME_LENGTH) }
    }
}

/**
 * The decrypted secret, returned only after password authentication (models-state.md §2.1). The receiver drops it as
 * soon as possible: [close] wipes both parts. Never cached, never logged ([toString] is redacted).
 */
class VaultSecret(val mnemonic: SecretText, val passphrase: SecretText) : AutoCloseable {
    override fun close() {
        mnemonic.wipe()
        passphrase.wipe()
    }

    override fun toString(): String = "VaultSecret(<redacted>)"
}

/** Public-data state of a wallet (models-state.md §2.2). [wire] goes to the engine as `publicMetadataStatus`. */
enum class PublicMetadataStatus(val wire: String) {
    Ready("ready"),
    NeedsCompletion("needsCompletion"),
    Invalid("invalid"),
}

/** What the public repository knows about one wallet (iOS `PublicWalletSnapshot`). */
data class PublicWalletSnapshot(
    val displayName: String?,
    val metadata: WalletPublicMetadata?,
    val status: PublicMetadataStatus,
)

/**
 * The frozen, public-only wallet index produced by the engine's `derivePublicIndex` (models-state.md §2.3, iOS
 * `Models.swift:470-531`). [raw] is kept exactly as received and goes back to the engine unchanged (engine-contract.md
 * §0 item 3); [canonicalJson] (sorted keys) is what the size limit, snapshot comparisons and the sidecar use.
 * Equality is by [canonicalJson].
 */
class WalletPublicMetadata private constructor(
    val raw: JsonObject,
    val canonicalJson: String,
    /** 64 lowercase hex characters: the identity used for duplicate detection and signing checks. */
    val walletKeyId: String,
    /** 8 lowercase hex characters: display only (shown uppercased). */
    val masterFingerprint: String,
) {
    /** The first EVM receive address (`evmDefaultAddress.address`), for summaries. */
    val evmAddress: String? get() = raw.obj("evmDefaultAddress")?.str("address")

    /** The fingerprint as people see it and as pairing QR codes export it (iOS `fingerprint(for:)`), e.g. `73C5DA0A`. */
    val displayFingerprint: String get() = masterFingerprint.uppercase(Locale.ROOT)

    override fun equals(other: Any?): Boolean = other is WalletPublicMetadata && other.canonicalJson == canonicalJson
    override fun hashCode(): Int = canonicalJson.hashCode()
    override fun toString(): String = "WalletPublicMetadata(walletKeyId=$walletKeyId)"

    companion object {
        const val MAX_CANONICAL_BYTES = 262_144
        private val EXPECTED_BTC = setOf("m/84'/0'/0'", "m/86'/0'/0'", "m/49'/0'/0'", "m/44'/0'/0'", "m/48'/0'/0'/2'", "m/48'/0'/0'/1'")
        private val EXPECTED_SOLANA = setOf("m/44'/501'/0'/0'", "m/44'/501'/0'", "m/44'/501'")
        private val FORBIDDEN_KEYS = setOf("mnemonic", "seed", "privatekey", "privateextendedkey", "password", "passphrase", "xprv", "secret")

        /**
         * Validates [raw] in iOS order and returns the index, or throws [AppIssue]:
         * 1. structure + public-only scan → "Invalid public wallet data. Repair this wallet.";
         * 2. origins, nodes, Solana and TRON entries → "Public wallet data is incomplete or inconsistent.";
         * 3. canonical JSON > 262,144 UTF-8 bytes → "Public wallet data is too large.".
         */
        fun parse(raw: JsonObject): WalletPublicMetadata {
            val key = raw.str("walletKeyId")
            val fingerprint = raw.str("masterFingerprint")
            val bitcoin = raw.objects("bitcoinPublicNodes")
            val evm = raw.obj("evmAccountNode")
            val tron = raw.obj("tronAccountNode")
            val tronAddresses = raw.objects("tronAddressIndex")
            val solana = raw.objects("solanaPublicKeys")
            if (raw.int("version") != 1 || !Hex.isHex(key, 32) || !Hex.isHex(fingerprint, 4) ||
                bitcoin == null || bitcoin.size != 6 || evm == null || tron == null ||
                tronAddresses == null || tronAddresses.size != 100 || solana == null || solana.size != 3 ||
                !publicOnly(raw, 0)
            ) {
                throw AppIssue("Invalid public wallet data. Repair this wallet.")
            }
            val consistent = bitcoin.mapNotNull { it.str("originPath") }.toSet() == EXPECTED_BTC &&
                evm.str("originPath") == AccountProfiles.EVM_ORIGIN &&
                tron.str("originPath") == AccountProfiles.TRON_ORIGIN &&
                (bitcoin + listOf(evm, tron)).all(::validNode) &&
                solana.mapNotNull { it.str("path") }.toSet() == EXPECTED_SOLANA &&
                solana.all { validAddress(it, 32) } &&
                tronAddresses.mapNotNull { it.int("index") }.toSet() == (0 until 100).toSet() &&
                tronAddresses.all { item ->
                    val index = item.int("index")
                    index != null && item.str("path") == "${AccountProfiles.TRON_PATH_PREFIX}$index" && validAddress(item, 33)
                }
            if (!consistent) throw AppIssue("Public wallet data is incomplete or inconsistent.")
            val canonical = raw.canonicalJson()
            if (canonical.utf8Size() > MAX_CANONICAL_BYTES) throw AppIssue("Public wallet data is too large.")
            return WalletPublicMetadata(raw, canonical, key!!.lowercase(Locale.ROOT), fingerprint!!.lowercase(Locale.ROOT))
        }

        private fun validNode(node: JsonObject): Boolean {
            val key = node.str("publicKey") ?: return false
            val code = node.str("chainCode") ?: return false
            val parent = node.str("parentFingerprint") ?: return false
            val depth = node.int("depth") ?: return false
            val xpub = node.str("xpub") ?: return false
            return Hex.isHex(key, 33) && (key.startsWith("02") || key.startsWith("03")) && Hex.isHex(code, 32) &&
                Hex.isHex(parent, 4) && depth in 3..4 && xpub.startsWith("xpub") && Graphemes.count(xpub) < 120
        }

        private fun validAddress(item: JsonObject, keyBytes: Int): Boolean {
            val key = item.str("publicKey") ?: return false
            val address = item.str("address") ?: return false
            return Hex.isHex(key, keyBytes) && address.isNotEmpty() && !Graphemes.exceeds(address, 128)
        }

        private fun publicOnly(value: JsonElement, depth: Int): Boolean {
            if (depth > 12) return false
            return when (value) {
                is JsonObject -> value.all { (k, v) -> k.lowercase(Locale.ROOT) !in FORBIDDEN_KEYS && publicOnly(v, depth + 1) }
                is JsonArray -> value.size <= 256 && value.all { publicOnly(it, depth + 1) }
                JsonNull -> true
                is JsonPrimitive -> !value.isString || (value.content.utf8Size() <= 2048 && !value.content.startsWith("xprv"))
            }
        }
    }
}

/**
 * One derived receive address from `derivePublicAddresses` (models-state.md §2.4). Unknown chain → Bitcoin, missing
 * strings → "", missing index → 0 (iOS defaults). [id] (`chain:path`) is the lazy-list key.
 */
data class PublicAccount(val chain: Chain, val address: String, val path: String, val publicKey: String, val index: Long) {
    val id: String get() = chain.wire + ":" + path

    companion object {
        fun parse(raw: JsonObject): PublicAccount = PublicAccount(
            chain = Chain.fromWire(raw.str("chain")) ?: Chain.Bitcoin,
            address = raw.str("address") ?: "",
            path = raw.str("path") ?: "",
            publicKey = raw.str("publicKey") ?: "",
            index = raw.long("index") ?: 0,
        )
    }
}
