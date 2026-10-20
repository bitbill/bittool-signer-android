package com.bittool.signer.core.engine

import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.signing.PlanCandidate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** Parameter building of [EngineCalls] (engine-contract.md §2.4, §6, §12.5) against a recording engine. */
class EngineCallsTest {
    private class RecordingEngine : SignerEngine {
        val calls = mutableListOf<Pair<EngineOperation, JsonObject>>()
        val scans = mutableListOf<String>()
        var released = 0

        override suspend fun run(operation: EngineOperation, parameters: JsonObject): JsonObject {
            calls += operation to parameters
            return JsonObject(mapOf("ok" to JsonPrimitive(true)))
        }

        override suspend fun beginScanSession() { scans += "begin" }
        override suspend fun scan(frame: String): JsonObject { scans += frame; return JsonObject(emptyMap()) }
        override suspend fun endScanSession() { scans += "end" }
        override suspend fun releaseWarmContext() { released++ }
        override suspend fun prewarm() = Unit
        override suspend fun bundleDigest() = BundleDigest("", 0, "")
    }

    private val engine = RecordingEngine()
    private val calls = EngineCalls(engine)
    private val wallet = WalletId.parse("10000000-0000-0000-0000-000000000001")!!

    private fun last(): Pair<EngineOperation, JsonObject> = engine.calls.last()

    private fun keys(o: JsonObject) = o.keys.sorted()

    @Test
    fun secretsAreSentAloneUnderTheirKey() = runTest {
        calls.mnemonic("00".repeat(16).toCharArray())
        assertEquals(EngineOperation.Mnemonic, last().first)
        assertEquals("""{"entropy":"${"00".repeat(16)}"}""", last().second.canonicalJson())
        calls.derivePublicIndex("ab".repeat(64).toCharArray())
        assertEquals(EngineOperation.DerivePublicIndex, last().first)
        assertEquals(listOf("seed"), keys(last().second), "never passphrase or mnemonic next to a seed")
    }

    @Test
    fun warmOperationsTakeTheAuthenticatedIndexUnchanged() = runTest {
        val index = publicIndex()
        calls.derivePublicAddresses(index, Chain.Solana, "", start = 0, count = 3)
        val (op, p) = last()
        assertEquals(EngineOperation.DerivePublicAddresses, op)
        assertSame(index.raw, p["publicIndex"], "the index object is forwarded as received")
        assertEquals("""{"chain":"solana","count":3,"profile":"","start":0}""", JsonObject(p - "publicIndex").canonicalJson())
        calls.derivePublicAddresses(index, Chain.Tron, "standard", start = 2_147_483_628, count = 20)
        assertEquals("2147483628", (last().second["start"] as JsonPrimitive).content)

        calls.createPublicPairing(index, Chain.Tron, "standard", "address", addressIndex = 3)
        assertEquals(EngineOperation.CreatePublicPairing, last().first)
        assertEquals(listOf("chain", "index", "outputMode", "profile", "publicIndex"), keys(last().second))
        assertEquals("""{"chain":"tron","index":3,"outputMode":"address","profile":"standard"}""", JsonObject(last().second - "publicIndex").canonicalJson())

        calls.createWalletPairing(index)
        assertEquals(EngineOperation.CreateWalletPairing, last().first)
        assertEquals("full", (last().second["mode"] as JsonPrimitive).content)
        assertEquals(listOf("mode", "publicIndex"), keys(last().second))
    }

    @Test
    fun theRequestPipelineForwardsPassThroughObjects() = runTest {
        val request = parseJsonObject("""{"chain":"evm","kind":"personal","transport":{"type":"eth-sign-request","payloadBytes":"a1"}}""")!!
        val plan = parseJsonObject("""{"status":"ready","planHash":"${"b".repeat(64)}","tasks":[]}""")!!
        val previous = parseJsonObject("""{"requestHash":"x","planHash":"y","completedTaskIds":["t0"]}""")!!

        calls.decodePayload("cHNidP8=")
        assertEquals("""{"payload":"cHNidP8="}""", last().second.canonicalJson())
        calls.decodeQr(listOf("UR:A/1", "UR:A/2"))
        assertEquals("""{"frames":["UR:A/1","UR:A/2"]}""", last().second.canonicalJson())
        calls.review(request)
        assertSame(request, last().second["request"])

        calls.signPlanTask(request, plan, "t1", "cd".repeat(64).toCharArray(), riskAcknowledged = false, previousResult = null)
        val (op, p) = last()
        assertEquals(EngineOperation.SignPlanTask, op)
        assertEquals(listOf("plan", "request", "riskAcknowledged", "seed", "taskId"), keys(p), "no previousResult key, not even null")
        assertSame(plan, p["plan"])
        assertEquals(JsonPrimitive(false), p["riskAcknowledged"])

        calls.signPlanTask(request, plan, "t2", "cd".repeat(64).toCharArray(), riskAcknowledged = true, previousResult = previous)
        assertSame(previous, last().second["previousResult"])
        assertEquals(JsonPrimitive(true), last().second["riskAcknowledged"])

        calls.encodeResponse(request, previous)
        assertEquals(listOf("request", "result"), keys(last().second))
    }

    @Test
    fun planCandidatesCarryAnIndexOrAStatus() = runTest {
        val index = publicIndex()
        val other = WalletId.parse("20000000-0000-0000-0000-000000000002")!!
        val request = JsonObject(mapOf("chain" to JsonPrimitive("evm")))
        calls.resolveSigningPlan(
            request,
            listOf(PlanCandidate(wallet, index, PublicMetadataStatus.Ready), PlanCandidate(other, null, PublicMetadataStatus.Invalid)),
        )
        val wallets = last().second["wallets"] as JsonArray
        val first = wallets[0] as JsonObject
        assertEquals(listOf("localWalletId", "publicIndex"), keys(first))
        assertEquals(JsonPrimitive("10000000-0000-0000-0000-000000000001"), first["localWalletId"])
        assertEquals("""{"localWalletId":"20000000-0000-0000-0000-000000000002","publicMetadataStatus":"invalid"}""", wallets[1].canonicalJson())
        assertFalse(last().second.toString().contains("null"))
    }

    @Test
    fun scanAndWarmLifecycleAreDelegated() = runTest {
        calls.beginScanSession()
        calls.scan(" \nUR:BYTES/TESTFRAME\n")
        calls.endScanSession()
        calls.releaseWarmContext()
        assertEquals(listOf("begin", " \nUR:BYTES/TESTFRAME\n", "end"), engine.scans, "frames are passed unchanged")
        assertEquals(1, engine.released)
    }

    /** A structurally valid public index (fake keys; never a funded wallet). */
    private fun publicIndex(): WalletPublicMetadata {
        fun node(origin: String, depth: Int = 3) = buildJsonObject {
            put("originPath", origin); put("publicKey", "02" + "11".repeat(32)); put("chainCode", "22".repeat(32))
            put("parentFingerprint", "7ef32bdb"); put("depth", depth); put("xpub", "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFk")
        }
        val raw = buildJsonObject {
            put("version", 1)
            put("walletKeyId", "99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96")
            put("masterFingerprint", "73c5da0a")
            put("bitcoinPublicNodes", JsonArray(listOf("m/84'/0'/0'", "m/86'/0'/0'", "m/49'/0'/0'", "m/44'/0'/0'").map { node(it) } + listOf("m/48'/0'/0'/2'", "m/48'/0'/0'/1'").map { node(it, 4) }))
            put("evmAccountNode", node("m/44'/60'/0'"))
            put("tronAccountNode", node("m/44'/195'/0'"))
            put("tronAddressIndex", JsonArray((0 until 100).map { i -> buildJsonObject { put("index", i); put("path", "m/44'/195'/0'/0/$i"); put("publicKey", "03" + "33".repeat(32)); put("address", "T$i") } }))
            put("solanaPublicKeys", JsonArray(listOf("m/44'/501'/0'/0'", "m/44'/501'/0'", "m/44'/501'").map { path -> buildJsonObject { put("path", path); put("publicKey", "44".repeat(32)); put("address", "So1") } }))
        }
        return WalletPublicMetadata.parse(raw)
    }
}
