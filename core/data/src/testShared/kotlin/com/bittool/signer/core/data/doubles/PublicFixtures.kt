package com.bittool.signer.core.data.doubles

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Published BIP39 fixtures and synthetic offline requests only (iOS SessionTests / ActivityRecordTests). Never fund them. */
object PublicFixtures {
    const val PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    const val ALTERNATE_PASSPHRASE = "Public alternate test wallet"
    /** The reference wallet's first EVM address (`m/44'/60'/0'/0/0`). */
    const val SIGNER = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    const val FINGERPRINT = "73c5da0a"
    const val WALLET_KEY_ID = "99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96"

    /**
     * An EVM transfer of 1 wei (SessionTests.swift:396-403). No funds and no network are involved; the transport names a
     * standard response profile so that a signature can be encoded (without it `encodeResponse` refuses).
     */
    fun evmRequest(nonce: Int = 0, data: String = "0x", transport: Boolean = true): JsonObject = buildJsonObject {
        put("chain", "evm")
        put("kind", "transaction")
        put("path", "m/44'/60'/0'/0/0")
        put("address", SIGNER)
        put("masterFingerprint", FINGERPRINT)
        if (transport) {
            put("transport", buildJsonObject { put("type", "eth-sign-request"); put("requestId", "00112233-4455-6677-8899-aabbccddeeff") })
        }
        put(
            "transaction",
            buildJsonObject {
                put("type", 2)
                put("chainId", "1")
                put("nonce", nonce)
                put("gasLimit", "21000")
                put("maxFeePerGas", "2000000000")
                put("maxPriorityFeePerGas", "1000000000")
                put("to", "0x1111111111111111111111111111111111111111")
                put("value", "1")
                put("data", data)
            },
        )
    }
}
