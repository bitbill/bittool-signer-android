package com.bittool.signer.feature.sign

import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.SigningPlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public test data for the Scan tab: engine-shaped review, plan and response documents (scan-sign.md §9–§10) and two
 * wallets with valid public indexes. No secret anywhere; the master fingerprint is the published BIP39 vector's.
 */
object SignFixtures {
    val walletA: WalletId = WalletId.parse("10000000-0000-0000-0000-000000000001")!!
    val walletB: WalletId = WalletId.parse("20000000-0000-0000-0000-000000000002")!!
    const val KEY_A = "99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96"
    const val KEY_B = "8f1c4b6e2a9d0f3e5c7b1a2d4e6f8091a3b5c7d9e1f20314253647586970a1b2"
    const val REQUEST_HASH = "3f2a91c40b77d2e85a10c3f98e4401ab7c55d0e2919f6b3a04ce7d81f2a9b360"
    const val PLAN_HASH = "b1f0c2d3e4a5968778695a4b3c2d1e0f1a2b3c4d5e6f708192a3b4c5d6e7f809"
    const val BTC_RECIPIENT = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    const val PERMIT2 = "0x000000000022D473030F116dDEE9F6B43aC78BA3"
    const val USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7"

    fun review(json: String): TransactionReview = TransactionReview.parse(parseJsonObject(json)!!)

    /** A fully decoded Bitcoin send with a verified change note (no acknowledgement needed). */
    val bitcoinSend: TransactionReview = review(
        """
        {"chain":"bitcoin","title":"Sign Bitcoin transaction","summary":"Send 0.001 BTC",
         "fields":[{"label":"Network","value":"Bitcoin"},{"label":"Output 1 · Recipient","value":"$BTC_RECIPIENT"},
                   {"label":"Output 1 · Amount","value":"0.001 BTC"},{"label":"Output 2 ownership","value":"Change returns to your wallet"},
                   {"label":"Network fee","value":"0.0000141 BTC"},{"label":"Inputs","value":"2"}],
         "warnings":[],"requestHash":"$REQUEST_HASH","canSign":true,"decodeStatus":"fullyDecoded","riskLevel":"normal",
         "network":{"name":"Bitcoin","symbol":"BTC"},
         "display":{"action":"Send","amount":"0.001","symbol":"BTC","counterpartyLabel":"To","counterparty":"$BTC_RECIPIENT",
                    "fee":"0.0000141 BTC","note":"Change returns to your wallet"}}
        """.trimIndent(),
    )

    /** An unlimited USDT approval to the known Permit2 spender: high risk, acknowledgement required. */
    fun knownSpenderApprovalJson(hash: String = REQUEST_HASH): String = """
        {"chain":"evm","title":"Authorize token spending","summary":"Approve unlimited USDT",
         "fields":[{"label":"Network","value":"Ethereum"},{"label":"Token","value":"$USDT"},{"label":"Spender","value":"$PERMIT2"},
                   {"label":"Spender (known contract)","value":"Permit2 — Uniswap · infra (same address on every EVM network)"},
                   {"label":"Allowance","value":"115792089237316195423570985008687907853269984665640564039457584007913129639935"},
                   {"label":"Requested signer","value":"0x9858EfFD232B4033E47d90003D41EC34EcaEda94"},
                   {"label":"Derivation path","value":"m/44'/60'/0'/0/0"}],
         "warnings":["UNLIMITED spending approval. The spender can move every USDT in this account.","Approvals stay in force until revoked."],
         "requestHash":"$hash","canSign":true,"decodeStatus":"fullyDecoded","riskLevel":"high",
         "network":{"chainId":"1","name":"Ethereum","symbol":"ETH"},
         "display":{"action":"Approve","headline":"Unlimited USDT","counterpartyLabel":"Spender","counterparty":"$PERMIT2","fee":"0.000396834604360128 ETH"},
         "contract":{"address":"$PERMIT2","role":"spender","known":true,"name":"Permit2","protocol":"Uniswap","category":"infra","everyChain":true}}
        """.trimIndent()

    val knownSpenderApproval: TransactionReview = review(knownSpenderApprovalJson())

    /** A request the engine could only partly decode: the "can't be fully verified" acknowledgement. */
    val partiallyDecoded: TransactionReview = review(
        """
        {"chain":"evm","title":"Contract call","summary":"Method 0x12345678",
         "fields":[{"label":"Network","value":"Base"},{"label":"Contract","value":"0x4200000000000000000000000000000000000006"},
                   {"label":"Data","value":"0x12345678000000000000000000000000000000000000000000000000000000000000002a"}],
         "warnings":["The call data could not be fully decoded on this device."],
         "requestHash":"$REQUEST_HASH","canSign":true,"decodeStatus":"partiallyDecoded","riskLevel":"warning",
         "network":{"chainId":"8453","name":"Base","symbol":"ETH"},
         "contract":{"address":"0x4200000000000000000000000000000000000006","role":"contract","known":false}}
        """.trimIndent(),
    )

    /** The long witness script of [batchPayout]: over 280 characters, so its row shows "Show All". */
    val witnessScript = "ab".repeat(200)

    /**
     * A batch payout PSBT as the engine reviews it (audit A10): a recipient and an amount row per output, as
     * `bitcoin.js` emits them, so [outputs] outputs make about twice as many Details rows; a long witness script near
     * the top. Public data only.
     */
    fun batchPayout(outputs: Int): TransactionReview {
        val fields = buildList {
            add("""{"label":"Network","value":"Bitcoin"}""")
            add("""{"label":"Input 1 witness script","value":"$witnessScript"}""")
            for (n in 1..outputs) {
                add("""{"label":"Output $n · Recipient","value":"$BTC_RECIPIENT"}""")
                add("""{"label":"Output $n · Amount","value":"0.0001 BTC"}""")
            }
            add("""{"label":"Network fee","value":"0.0021 BTC"}""")
        }
        return review(
            """
            {"chain":"bitcoin","title":"Sign Bitcoin transaction","summary":"Send ${outputs / 10_000.0} BTC to $outputs recipients",
             "fields":[${fields.joinToString(",")}],
             "warnings":[],"requestHash":"$REQUEST_HASH","canSign":true,"decodeStatus":"fullyDecoded","riskLevel":"normal",
             "network":{"name":"Bitcoin","symbol":"BTC"},
             "display":{"action":"Send","amount":"${outputs / 10_000.0}","symbol":"BTC","fee":"0.0021 BTC"}}
            """.trimIndent(),
        )
    }

    private fun taskJson(id: String, wallet: WalletId, key: String, chain: String = "bitcoin", inputs: List<Int> = listOf(0, 1)) = buildJsonObject {
        put("id", id)
        put("localWalletId", wallet.toString())
        put("walletKeyId", key)
        put("chain", chain)
        put("paths", JsonArray(listOf(JsonPrimitive("m/84'/0'/0'/0/0"), JsonPrimitive("m/84'/0'/0'/1/3"))))
        put("publicKeys", JsonArray(listOf(JsonPrimitive("02" + "11".repeat(32)), JsonPrimitive("03" + "22".repeat(32)))))
        put("addresses", JsonArray(listOf(JsonPrimitive(BTC_RECIPIENT))))
        put("inputs", JsonArray(inputs.map(::JsonPrimitive)))
        put("targets", JsonArray(listOf(buildJsonObject { put("path", "m/84'/0'/0'/0/0") })))
    }

    /** A ready plan for [REQUEST_HASH] with one task per entry, in order. */
    fun plan(vararg tasks: Pair<String, WalletId>): SigningPlan {
        val raw = buildJsonObject {
            put("version", 1)
            put("status", "ready")
            put("requestHash", REQUEST_HASH)
            put("planHash", PLAN_HASH)
            put("chain", "bitcoin")
            put("tasks", JsonArray(tasks.map { (id, wallet) -> taskJson(id, wallet, if (wallet == walletB) KEY_B else KEY_A) }))
        }
        return SigningPlan.parse(raw, REQUEST_HASH)
    }

    /** The response of a PSBT request as `encodeResponse` describes it. */
    val psbtPayload: QrPayloadView = QrPayloadView.parse(
        parseJsonObject(
            """{"frames":["UR:CRYPTO-PSBT/1-2/LPADAOCF"],"type":"crypto-psbt","payloadFormat":"json",
                "payload":{"psbt_base64":"cHNidP8BAHECAAAAAQ==","decoded":{"inputs":2,"outputs":2}},"payloadBytes":412}""",
        )!!,
    )!!

    val responseFrames = listOf(
        "UR:CRYPTO-PSBT/1-3/LPADAXCFAXHLCYYNSBLBGEHDOSOLEOEOEOEOEOEOEOEOEOEOEOEOEOEOEOEOE",
        "UR:CRYPTO-PSBT/2-3/LPAOAXCFAXHLCYYNSBLBGEHDOSOLEOEOEOEOEOEOEOEOEOEOEOEOEOEOEOEOE",
        "UR:CRYPTO-PSBT/3-3/LPAXAXCFAXHLCYYNSBLBGEHDOSOLEOEOEOEOEOEOEOEOEOEOEOEOEOEOEOEOE",
    )

    // ---- Wallets with valid public indexes (WalletModelsTest shape) -------------------------------------------------

    private fun node(origin: String, depth: Int = 3, key: String = "02" + "11".repeat(32)) = buildJsonObject {
        put("chain", "bitcoin"); put("originPath", origin); put("accountPath", origin); put("path", origin)
        put("xpub", "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V")
        put("publicKey", key); put("compressedPublicKey", key); put("chainCode", "4a".repeat(32)); put("parentFingerprint", "7ef32bdb")
        put("depth", depth); put("scriptProfile", "native"); put("children", JsonArray(listOf(JsonPrimitive("0/*")))); put("masterFingerprint", "73c5da0a")
    }

    private fun tronEntry(i: Int) = buildJsonObject {
        put("chain", "tron"); put("profile", "standard"); put("path", "m/44'/195'/0'/0/$i"); put("index", i); put("branch", 0)
        put("publicKey", "03" + "22".repeat(32)); put("address", "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"); put("masterFingerprint", "73c5da0a")
    }

    private fun solana(profile: String, path: String) = buildJsonObject {
        put("chain", "solana"); put("profile", profile); put("path", path); put("index", 0)
        put("publicKey", "33".repeat(32)); put("address", "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"); put("masterFingerprint", "73c5da0a")
    }

    fun publicIndex(key: String, fingerprint: String): WalletPublicMetadata {
        val map = linkedMapOf<String, JsonElement>(
            "version" to JsonPrimitive(1),
            "walletKeyId" to JsonPrimitive(key),
            "masterFingerprint" to JsonPrimitive(fingerprint),
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
        )
        return WalletPublicMetadata.parse(JsonObject(map))
    }

    /** Two ready wallets, "Public fixture 1" and "Travel Signer". */
    fun walletsState(base: SignerState = SignerState(isLoading = false, walletLoadSucceeded = true)): SignerState = base.copy(
        wallets = listOf(VaultMetadata(walletA, "Public fixture 1", 1_000), VaultMetadata(walletB, "Travel Signer", 2_000)),
        publicWallets = mapOf(walletA to publicIndex(KEY_A, "73c5da0a"), walletB to publicIndex(KEY_B, "1a2b3c4d")),
        metadataStatuses = mapOf(walletA to PublicMetadataStatus.Ready, walletB to PublicMetadataStatus.Ready),
    )
}
