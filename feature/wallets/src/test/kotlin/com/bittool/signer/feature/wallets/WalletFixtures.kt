package com.bittool.signer.feature.wallets

import androidx.lifecycle.SavedStateHandle
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.pairing.PairingPayload
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.pairing.WalletPairingPayload
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public test data for the Wallets screens: the published BIP39 test wallet (`abandon ×11 about`, fingerprint
 * `73C5DA0A`) as an engine-shaped public index, and pairing payloads with the real vectors of wallets-pairing.md §2.
 * Public keys only; nothing here is secret.
 */
object WalletFixtures {
    val readyId = WalletId(UUID.fromString("5f0b2a52-2a5e-4f43-9a51-3f7c2c0b9a01"))
    val repairId = WalletId(UUID.fromString("5f0b2a52-2a5e-4f43-9a51-3f7c2c0b9a02"))
    val twinId = WalletId(UUID.fromString("5f0b2a52-2a5e-4f43-9a51-3f7c2c0b9a03"))

    /** 1 Oct 2026, 12:00 UTC. */
    const val CREATED_AT = 1_790_856_000_000L

    private const val KEY = "02" + "11"

    private fun node(origin: String, depth: Int = 3) = buildJsonObject {
        put("chain", "bitcoin"); put("originPath", origin); put("accountPath", origin); put("path", origin)
        put("xpub", "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V")
        put("publicKey", "02" + "11".repeat(32)); put("chainCode", "4a".repeat(32)); put("parentFingerprint", "7ef32bdb")
        put("depth", depth); put("masterFingerprint", "73c5da0a")
    }

    private fun tronEntry(i: Int) = buildJsonObject {
        put("chain", "tron"); put("profile", "standard"); put("path", "m/44'/195'/0'/0/$i"); put("index", i)
        put("publicKey", "03" + "22".repeat(32)); put("address", "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH")
    }

    private fun solana(profile: String, path: String) = buildJsonObject {
        put("chain", "solana"); put("profile", profile); put("path", path); put("index", 0)
        put("publicKey", "33".repeat(32)); put("address", "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk")
    }

    /** An authenticated public index as `derivePublicIndex` returns it (validated by [WalletPublicMetadata.parse]). */
    fun publicIndex(walletKeyId: String = "99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96"): WalletPublicMetadata =
        WalletPublicMetadata.parse(
            JsonObject(
                linkedMapOf(
                    "version" to JsonPrimitive(1),
                    "walletKeyId" to JsonPrimitive(walletKeyId),
                    "masterFingerprint" to JsonPrimitive("73c5da0a"),
                    "bitcoinPublicNodes" to JsonArray(
                        listOf("m/84'/0'/0'", "m/86'/0'/0'", "m/49'/0'/0'", "m/44'/0'/0'").map { node(it) } +
                            listOf("m/48'/0'/0'/2'", "m/48'/0'/0'/1'").map { node(it, depth = 4) },
                    ),
                    "evmAccountNode" to node("m/44'/60'/0'"),
                    "tronAccountNode" to node("m/44'/195'/0'"),
                    "evmDefaultAddress" to buildJsonObject { put("address", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94") },
                    "tronAddressIndex" to JsonArray((0 until 100).map(::tronEntry)),
                    "solanaPublicKeys" to JsonArray(
                        listOf(solana("standard", "m/44'/501'/0'/0'"), solana("trust", "m/44'/501'/0'"), solana("legacy", "m/44'/501'")),
                    ),
                ),
            ),
        )

    /** One ready wallet ("Public fixture 1"), optionally a wallet that needs repair and a duplicate pair. */
    fun state(repair: Boolean = false, duplicates: Boolean = false): SignerState {
        val wallets = buildList {
            add(VaultMetadata(readyId, "Public fixture 1", CREATED_AT))
            if (repair) add(VaultMetadata(repairId, "Travel Signer", CREATED_AT + 60_000))
            if (duplicates) add(VaultMetadata(twinId, "Public fixture 1 copy", CREATED_AT + 120_000))
        }
        val index = publicIndex()
        val public = buildMap {
            put(readyId, index)
            if (duplicates) put(twinId, index)
        }
        return SignerState(
            wallets = wallets,
            isLoading = false,
            walletLoadSucceeded = true,
            publicWallets = public,
            metadataStatuses = wallets.associate { wallet ->
                wallet.id to if (public.containsKey(wallet.id)) PublicMetadataStatus.Ready else PublicMetadataStatus.NeedsCompletion
            },
            duplicateWalletIds = if (duplicates) setOf(readyId, twinId) else emptySet(),
        )
    }

    fun store(state: SignerState = state()): FakeSignerStore = FakeSignerStore(state)

    /** The arguments Navigation stores for a Wallets route. */
    fun args(walletId: WalletId = readyId, level: String? = null, chain: String? = null): SavedStateHandle =
        SavedStateHandle(
            buildMap {
                put("walletId", walletId.toString())
                if (level != null) put("level", level)
                if (chain != null) put("chain", chain)
            },
        )

    /** `derivePublicAddresses` rows: [count] receive addresses from [start] (BIP84-looking, deterministic). */
    fun accounts(chain: Chain, start: Long, count: Int): List<PublicAccount> = (0 until count).map { offset ->
        val index = start + offset
        when (chain) {
            Chain.Bitcoin -> PublicAccount(chain, "bc1q" + index.toString().padStart(38, 'q'), "m/84'/0'/0'/0/$index", KEY, index)
            Chain.Evm -> PublicAccount(chain, "0x" + index.toString().padStart(40, '0'), "m/44'/60'/0'/0/$index", KEY, index)
            Chain.Tron -> PublicAccount(chain, "T" + index.toString().padStart(33, 'U'), "m/44'/195'/0'/0/$index", KEY, index)
            Chain.Solana -> error("use solanaAccounts()")
        }
    }

    /** The three Solana presets (`profile ""`). */
    val solanaAccounts: List<PublicAccount> = listOf(
        PublicAccount(Chain.Solana, "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", "m/44'/501'/0'/0'", KEY, 0),
        PublicAccount(Chain.Solana, "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL", "m/44'/501'/0'", KEY, 0),
        PublicAccount(Chain.Solana, "D2PPQSYFe83nDzk96FqGumVU8JA7J8vj2Rhjc2oXzEi5", "m/44'/501'", KEY, 0),
    )

    /** Coin Level BTC native account export (wallets-pairing §2.2 vector). */
    fun coinPayload(path: String = "m/84'/0'/0'", address: String = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", mode: String = "accountXpub") =
        PairingPayload(
            frames = listOf("UR:CRYPTO-ACCOUNT/OEADCYJKSKTNBKAOLYTAADMETAADDLOLAOWKAXHDCLAXWMFMDEIAMECSDSEMGTVSJZCNCYGRKOWTRONTZSCHGEZOKSTSWKKSCFMSSRPKBDMWYAHDCXJTPDYTFBNDNEMTFNKKSTPGLYKNYLAOSRYFAKPNEKTHRZDYVEOLHT".take(80)),
            text = "UR:CRYPTO-ACCOUNT/OEADCY",
            address = address,
            path = path,
            outputMode = mode,
            note = null,
            payloadView = QrPayloadView(
                format = QrPayloadView.Format.Json,
                text = "{\n  \"master-fingerprint\" : 1942346250\n}",
                byteCount = 170,
                qrType = "crypto-account",
                bbqrEncoding = null,
            ),
        )

    /** Wallet Level export: 4 frames, the 9 verification rows in engine order (wallets-pairing §2.3). */
    fun walletPayload(): WalletPairingPayload = WalletPairingPayload(
        frames = (1..4).map { "UR:BYTES/$it-4/LPADAACFAMC" + "A".repeat(40) },
        text = (1..4).joinToString("\n") { "UR:BYTES/$it-4/LPADAACFAMC" + "A".repeat(40) },
        mode = "full",
        payloadBytes = 1561,
        firstAddresses = listOf(
            WalletPairingPayload.Line("btc", "native", "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", "m/84'/0'/0'/0/0"),
            WalletPairingPayload.Line("btc", "taproot", "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", "m/86'/0'/0'/0/0"),
            WalletPairingPayload.Line("btc", "nested", "37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf", "m/49'/0'/0'/0/0"),
            WalletPairingPayload.Line("btc", "legacy", "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", "m/44'/0'/0'/0/0"),
            WalletPairingPayload.Line("evm", "standard", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94", "m/44'/60'/0'/0/0"),
            WalletPairingPayload.Line("tron", "standard", "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", "m/44'/195'/0'/0/0"),
            WalletPairingPayload.Line("sol", "standard", "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", "m/44'/501'/0'/0'"),
            WalletPairingPayload.Line("sol", "trust", "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL", "m/44'/501'/0'"),
            WalletPairingPayload.Line("sol", "legacy", "D2PPQSYFe83nDzk96FqGumVU8JA7J8vj2Rhjc2oXzEi5", "m/44'/501'"),
        ),
        json = "{\n  \"masterFingerprint\" : \"73c5da0a\",\n  \"protocol\" : \"bittool-signer-wallet\"\n}",
    )
}
