package com.bittool.signer.core.data

import com.bittool.signer.core.engine.BundleDigest
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.engine.SignerEngine
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.util.Hex
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A scripted [SignerEngine] for the JVM pipeline tests: it produces **structurally valid** engine documents (a public
 * index that passes `WalletPublicMetadata.parse`, reviews with a 64-hex commitment, ready plans for the matching wallet,
 * results that echo the plan identity) without QuickJS. Identities are a function of the seed, so the same phrase and
 * passphrase always give the same `walletKeyId`, like the real engine. Any operation can be replaced with [on].
 * The real engine is exercised by the instrumented tests.
 */
class FakeEngine : SignerEngine {
    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val handlers = ConcurrentHashMap<EngineOperation, suspend (JsonObject) -> JsonObject>()

    /** The frames of the open scan session, or null when none is open (`scanAdd` then fails like the real host). */
    var scanSession: MutableList<String>? = null
    var scanHandler: suspend (String) -> JsonObject = { frame ->
        if (frame == COMPLETE_FRAME) {
            JsonObject(mapOf("complete" to JsonPrimitive(true), "progress" to JsonPrimitive(1), "request" to request()))
        } else {
            JsonObject(mapOf("complete" to JsonPrimitive(false), "progress" to JsonPrimitive(0.25)))
        }
    }

    fun on(operation: EngineOperation, handler: suspend (JsonObject) -> JsonObject) {
        handlers[operation] = handler
    }

    fun count(operation: String): Int = calls.count { it.first == operation }

    override suspend fun run(operation: EngineOperation, parameters: JsonObject): JsonObject {
        calls += operation.wireName to parameters
        handlers[operation]?.let { return it(parameters) }
        return when (operation) {
            EngineOperation.Mnemonic -> JsonObject(mapOf("mnemonic" to JsonPrimitive(PHRASE)))
            EngineOperation.DerivePublicIndex -> publicIndex(parameters.str("seed") ?: error("seed"))
            EngineOperation.Review -> review(parameters.obj("request") ?: error("request"))
            EngineOperation.ResolveSigningPlan -> resolve(parameters)
            EngineOperation.SignPlanTask -> sign(parameters)
            EngineOperation.EncodeResponse -> encode()
            EngineOperation.DecodePayload, EngineOperation.DecodeQR ->
                JsonObject(mapOf("complete" to JsonPrimitive(true), "progress" to JsonPrimitive(1), "request" to request()))
            EngineOperation.CreateWalletPairing -> walletPairing()
            EngineOperation.DerivePublicAddresses -> JsonObject(mapOf("addresses" to JsonArray(emptyList())))
            else -> throw EngineException("UNSCRIPTED", "Unscripted ${operation.wireName}")
        }
    }

    override suspend fun beginScanSession() {
        calls += "scanBegin" to JsonObject(emptyMap())
        scanSession = mutableListOf()
    }

    override suspend fun scan(frame: String): JsonObject {
        calls += "scanAdd" to JsonObject(mapOf("frame" to JsonPrimitive(frame)))
        val session = scanSession ?: throw EngineException(EngineException.SCAN, "The scan session ended. Scan again.")
        session += frame
        return scanHandler(frame)
    }

    override suspend fun endScanSession() {
        scanSession = null
    }

    override suspend fun releaseWarmContext() {
        calls += "releaseWarmContext" to JsonObject(emptyMap())
    }

    override suspend fun prewarm() = Unit

    override suspend fun bundleDigest(): BundleDigest = BundleDigest("0".repeat(64), 1, "test")

    companion object {
        const val PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val COMPLETE_FRAME = "UR:ETH-SIGN-REQUEST/COMPLETE"

        /** A synthetic EVM transaction request (the iOS SessionTests shape). */
        fun request(nonce: Int = 0): JsonObject = buildJsonObject {
            put("chain", "evm")
            put("kind", "transaction")
            put("path", "m/44'/60'/0'/0/0")
            put("address", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
            put("masterFingerprint", "73c5da0a")
            put("transaction", buildJsonObject { put("nonce", nonce); put("to", "0x1111111111111111111111111111111111111111"); put("value", "1") })
        }

        fun sha256Hex(text: String): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()))

        /** The synthetic commitment of [request]: SHA-256 of its canonical JSON. */
        fun requestHash(request: JsonObject): String = sha256Hex(request.canonicalJson())

        /** A public index that passes every structural check of `WalletPublicMetadata.parse`, derived from [seed]. */
        fun publicIndex(seed: String): JsonObject {
            val key = sha256Hex("key:$seed")
            val fingerprint = sha256Hex("fp:$seed").take(8)
            fun hex(tag: String, bytes: Int) = (sha256Hex("$tag:$seed") + sha256Hex("$tag+:$seed")).take(bytes * 2)
            fun node(origin: String, depth: Int) = buildJsonObject {
                put("originPath", origin)
                put("publicKey", "02" + hex("pub$origin", 32))
                put("chainCode", hex("cc$origin", 32))
                put("parentFingerprint", hex("pf$origin", 4))
                put("depth", depth)
                put("xpub", "xpub" + hex("x$origin", 40))
            }
            return buildJsonObject {
                put("version", 1)
                put("walletKeyId", key)
                put("masterFingerprint", fingerprint)
                put("bitcoinPublicNodes", buildJsonArray {
                    listOf("m/84'/0'/0'", "m/86'/0'/0'", "m/49'/0'/0'", "m/44'/0'/0'").forEach { add(node(it, 3)) }
                    listOf("m/48'/0'/0'/2'", "m/48'/0'/0'/1'").forEach { add(node(it, 4)) }
                })
                put("evmAccountNode", node(AccountProfiles.EVM_ORIGIN, 3))
                put("tronAccountNode", node(AccountProfiles.TRON_ORIGIN, 3))
                put("evmDefaultAddress", buildJsonObject { put("address", "0x" + hex("evm", 20)); put("path", "m/44'/60'/0'/0/0") })
                put("tronAddressIndex", buildJsonArray {
                    for (i in 0 until 100) {
                        add(buildJsonObject {
                            put("index", i)
                            put("path", "${AccountProfiles.TRON_PATH_PREFIX}$i")
                            put("publicKey", "03" + hex("tron$i", 32))
                            put("address", "T" + hex("ta$i", 16))
                        })
                    }
                })
                put("solanaPublicKeys", buildJsonArray {
                    listOf("m/44'/501'/0'/0'", "m/44'/501'/0'", "m/44'/501'").forEach { path ->
                        add(buildJsonObject { put("path", path); put("publicKey", hex("sol$path", 32)); put("address", "S" + hex("sa$path", 16)) })
                    }
                })
            }
        }

        fun review(request: JsonObject, hash: String = requestHash(request)): JsonObject = buildJsonObject {
            put("chain", request.str("chain") ?: "evm")
            put("title", "Send ETH")
            put("summary", "1 wei to 0x1111…1111")
            put("fields", buildJsonArray { add(buildJsonObject { put("label", "Network"); put("value", "Ethereum") }) })
            put("warnings", JsonArray(emptyList()))
            put("requestHash", hash)
            put("decodeStatus", "fullyDecoded")
            put("riskLevel", "normal")
            put("canSign", true)
        }

        /** A ready plan with one task for the first candidate that has a public index (the "matching" wallet). */
        fun resolve(parameters: JsonObject): JsonObject {
            val request = parameters.obj("request") ?: error("request")
            val wallet = parameters.objects("wallets")?.firstOrNull { it.obj("publicIndex") != null }
                ?: return JsonObject(mapOf("status" to JsonPrimitive("noMatch"), "message" to JsonPrimitive("No matching wallet")))
            val index = wallet.obj("publicIndex")!!
            return plan(request, listOf(task("task-0", wallet.str("localWalletId")!!, index.str("walletKeyId")!!)))
        }

        fun task(id: String, walletId: String, walletKeyId: String): JsonObject = buildJsonObject {
            put("id", id)
            put("localWalletId", walletId)
            put("walletKeyId", walletKeyId)
            put("chain", "evm")
            put("paths", buildJsonArray { add(JsonPrimitive("m/44'/60'/0'/0/0")) })
            put("publicKeys", buildJsonArray { add(JsonPrimitive("02" + "11".repeat(32))) })
            put("addresses", buildJsonArray { add(JsonPrimitive("0x9858EfFD232B4033E47d90003D41EC34EcaEda94")) })
        }

        fun plan(request: JsonObject, tasks: List<JsonObject>, review: JsonElement? = null): JsonObject = buildJsonObject {
            put("version", 1)
            put("status", "ready")
            put("requestHash", requestHash(request))
            put("planHash", "b".repeat(64))
            put("tasks", JsonArray(tasks))
            if (review != null) put("review", review)
        }

        fun sign(parameters: JsonObject): JsonObject {
            val plan = parameters.obj("plan") ?: error("plan")
            return buildJsonObject {
                put("requestHash", plan.str("requestHash"))
                put("planHash", plan.str("planHash"))
                put("taskId", parameters.str("taskId"))
                put("signature", "ab".repeat(65))
            }
        }

        fun encode(): JsonObject = buildJsonObject {
            put("type", "eth-signature")
            put("frames", buildJsonArray { add(JsonPrimitive("UR:ETH-SIGNATURE/SYNTHETIC")) })
            put("payloadFormat", "json")
            put("payload", buildJsonObject { put("signature", "ab".repeat(65)) })
            put("payloadBytes", 89)
        }

        fun walletPairing(): JsonObject = buildJsonObject {
            put("type", "bytes")
            put("frames", buildJsonArray { add(JsonPrimitive("UR:BYTES/1-2/A")); add(JsonPrimitive("UR:BYTES/2-2/B")) })
            put("mode", "full")
            put("payloadBytes", 1561)
            put("payload", buildJsonObject { put("protocol", "bittool-signer-wallet") })
            put("verify", JsonArray(emptyList()))
        }
    }
}
