package com.bittool.signer.core.engine

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bittool.signer.core.model.json.canonicalJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KH v2 (1.1.0) on the real QuickJS engine, against the standard the iOS app produced: the identity-signature vectors
 * (`docs/kh2_bittool_vectors.json`) and the wallet-pairing handoff fixtures (iOS `docs/bittool-handoff/fixtures/`),
 * copied unchanged into `assets/kh2/` by `tools/engine/sync-from-ios.sh`. Equal UR frames mean equal canonical CBOR
 * bytes: the frames are a deterministic function of the bytes and decode back to exactly them.
 *
 * Public BIP39 test vector only (abandon ×11 about, no passphrase). Never a real wallet.
 */
@RunWith(AndroidJUnit4::class)
class KhV2VectorsTest {
    private val engine = EngineModule.create(ApplicationProvider.getApplicationContext(), allowTestOperations = true)

    /** The BIP39 seed of the vectors' mnemonic with an empty passphrase. */
    private val seed = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"

    private fun asset(name: String): JsonObject =
        InstrumentationRegistry.getInstrumentation().context.assets.open("kh2/$name").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun JsonElement?.text(): String = this!!.jsonPrimitive.content

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun sign(epoch: Int, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
        engine.run(EngineOperation.SignKhV2, JsonObject(mapOf("seed" to JsonPrimitive(seed), "epoch" to JsonPrimitive(epoch)) + extra))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun signKhV2ReproducesEveryIosVector() = runBlocking<Unit> {
        val vectors = asset("kh2_bittool_vectors.json")
        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", vectors["mnemonic"].text())
        assertEquals("", vectors["passphrase"].text())
        assertEquals("m/44'/0'/0'/0/1000000000", vectors["path"].text(), "BIP44, as both BitTool Wallet apps register it")
        val list = vectors["vectors"]!!.jsonArray
        assertTrue(list.size >= 2)
        for (element in list) {
            val vector = element.jsonObject
            val epoch = vector["epoch"]!!.jsonPrimitive.int
            val result = sign(epoch)
            for (field in listOf("message", "path", "publicKey", "address", "signature", "kh2")) {
                assertEquals(vector[field].text(), result[field].text(), "epoch $epoch: $field")
            }
            assertEquals(epoch, result["epoch"]!!.jsonPrimitive.int)
            assertEquals("OwnbitAuth:v2:epoch=$epoch", result["message"].text())
            // 65 bytes: header 27 + recovery id + 4 (compressed key) | R | S, low S.
            val bytes = Base64.decode(result["signature"].text(), Base64.NO_WRAP)
            assertEquals(65, bytes.size)
            assertTrue(bytes[0].toInt() in 31..34, "compressed-key header")
            assertEquals(vector["r"].text(), hex(bytes.copyOfRange(1, 33)), "epoch $epoch: R")
            assertEquals(vector["s"].text(), hex(bytes.copyOfRange(33, 65)), "epoch $epoch: S")
            assertTrue(bytes[33].toInt() and 0x80 == 0, "low S (the upper bit of S is clear)")
        }
    }

    @Test
    fun theIdentityKeyIsTheOneTheReceiverDerivesFromPublicData() = runBlocking<Unit> {
        // The receiving wallet and the backend only see the BIP44 account; the key must be reachable from it.
        val index = engine.run(EngineOperation.DerivePublicIndex, params("seed" to JsonPrimitive(seed)))
        val result = sign(1)
        assertEquals(index["walletKeyId"].text(), result["walletKeyId"].text(), "filed under the wallet it belongs to")
        val pairing = engine.run(
            EngineOperation.CreateWalletPairing,
            params("publicIndex" to index, "mode" to JsonPrimitive("full"), "khSig" to result["signature"]!!, "khEpoch" to JsonPrimitive(1)),
        )
        val identity = pairing["identity"]!!.jsonObject
        assertEquals(result["publicKey"].text(), identity["publicKey"].text())
        assertEquals(result["address"].text(), identity["address"].text())
        assertEquals("m/44'/0'/0'/0/1000000000", identity["path"].text())
    }

    @Test
    fun onlyTheFixedTextIsEverSigned() = runBlocking<Unit> {
        val vectors = asset("kh2_bittool_vectors.json")["vectors"]!!.jsonArray
        val first = vectors.first().jsonObject
        // The operation has no message parameter: an injected one changes nothing.
        val injected = sign(1, mapOf("message" to JsonPrimitive("Send 1 BTC to me"), "text" to JsonPrimitive("hello")))
        assertEquals("OwnbitAuth:v2:epoch=1", injected["message"].text())
        assertEquals(first["signature"].text(), injected["signature"].text())
        // Epochs outside 1…1,000,000 are refused, not signed.
        for (epoch in listOf(0, -1, 1_000_001)) {
            assertFailsWith<EngineException>("epoch $epoch") { sign(epoch) }
        }
        assertFailsWith<EngineException> {
            engine.run(EngineOperation.SignKhV2, params("seed" to JsonPrimitive(seed), "epoch" to JsonPrimitive("1")))
        }
    }

    @Test
    fun theWalletLevelCodeIsByteIdenticalWithTheIosFixture() = runBlocking<Unit> {
        val fixture = asset("wallet-pairing-current.json")
        assertEquals(2, fixture["protocolVersion"]!!.jsonPrimitive.int)
        val index = engine.run(EngineOperation.DerivePublicIndex, params("seed" to JsonPrimitive(seed)))
        val identity = sign(1)
        val result = engine.run(
            EngineOperation.CreateWalletPairing,
            params("publicIndex" to index, "mode" to JsonPrimitive("full"), "khSig" to identity["signature"]!!, "khEpoch" to JsonPrimitive(1)),
        )
        val frames = result["frames"]!!.jsonArray.map { it.text() }
        assertEquals(fixture["frames"]!!.jsonArray.map { it.text() }, frames, "UR frames, character for character")
        assertEquals(fixture["innerCBORBytes"]!!.jsonPrimitive.int, result["payloadBytes"]!!.jsonPrimitive.int)
        assertEquals(1666, result["payloadBytes"]!!.jsonPrimitive.int)
        assertEquals(5, frames.size)
        assertEquals(fixture["logicalPayload"]!!.jsonObject.canonicalJson(), result["payload"]!!.jsonObject.canonicalJson())
        val payload = result["payload"]!!.jsonObject
        assertEquals(2, payload["version"]!!.jsonPrimitive.int)
        assertEquals(1, payload["khEpoch"]!!.jsonPrimitive.int)
        assertEquals(identity["signature"].text(), payload["khSig"].text())
        assertEquals(Json.encodeToString(JsonElement.serializer(), fixture["verify"]!!), Json.encodeToString(JsonElement.serializer(), result["verify"]!!))
        val expectedIdentity = fixture["identity"]!!.jsonObject
        val actualIdentity = result["identity"]!!.jsonObject
        for (field in listOf("path", "publicKey", "address")) assertEquals(expectedIdentity[field].text(), actualIdentity[field].text(), field)
        // The canonical CBOR starts with the iOS bytes too: map(8) "mode" "full" "khSig" … (key order: length, then bytes).
        val innerHex = fixture["innerCBORHex"].text()
        assertTrue(innerHex.startsWith("a8646d6f64656466756c6c656b68536967"), "fixture shape")
        assertTrue(fixture["urCBORHex"].text().endsWith(innerHex), "the UR body wraps exactly the inner CBOR")
    }

    @Test
    fun aWalletLevelCodeWithoutAMatchingIdentityIsRefused() = runBlocking<Unit> {
        val index = engine.run(EngineOperation.DerivePublicIndex, params("seed" to JsonPrimitive(seed)))
        fun pairing(vararg extra: Pair<String, JsonElement>) = params("publicIndex" to index, "mode" to JsonPrimitive("full"), *extra)
        // No v1 any more: no signature, no code.
        assertFailsWith<EngineException> { engine.run(EngineOperation.CreateWalletPairing, pairing()) }
        // The epoch-2 signature is not valid for epoch 1, and a damaged one is not valid at all.
        val vectors = asset("kh2_bittool_vectors.json")["vectors"]!!.jsonArray
        val epoch2 = vectors.first { it.jsonObject["epoch"]!!.jsonPrimitive.int == 2 }.jsonObject["signature"]!!
        assertFailsWith<EngineException> {
            engine.run(EngineOperation.CreateWalletPairing, pairing("khSig" to epoch2, "khEpoch" to JsonPrimitive(1)))
        }
        val good = sign(1)["signature"].text()
        val damaged = good.substring(0, 10) + (if (good[10] == 'A') 'B' else 'A') + good.substring(11)
        assertFailsWith<EngineException> {
            engine.run(EngineOperation.CreateWalletPairing, pairing("khSig" to JsonPrimitive(damaged), "khEpoch" to JsonPrimitive(1)))
        }
        // Another wallet's signature (a passphrase makes another wallet) is refused against this index.
        val other = "d9afbd2c0f5f11c3a0bd7bd2c8c8c0e6b5c4a1f0d7d6e1b2c3a4f5e6d7c8b9a0d9afbd2c0f5f11c3a0bd7bd2c8c8c0e6b5c4a1f0d7d6e1b2c3a4f5e6d7c8b9a0"
        val foreign = engine.run(EngineOperation.SignKhV2, params("seed" to JsonPrimitive(other), "epoch" to JsonPrimitive(1)))["signature"]!!
        assertFailsWith<EngineException> {
            engine.run(EngineOperation.CreateWalletPairing, pairing("khSig" to foreign, "khEpoch" to JsonPrimitive(1)))
        }
    }

    @Test
    fun theV1FixtureIsKeptOnlyAsWhatReceiversMustRefuse() {
        val rejected = asset("wallet-pairing-v1-rejected.json")
        val payload = rejected["logicalPayload"]!!.jsonObject
        assertEquals(1, payload["version"]!!.jsonPrimitive.int)
        assertTrue("khSig" !in payload && "khEpoch" !in payload)
    }
}
