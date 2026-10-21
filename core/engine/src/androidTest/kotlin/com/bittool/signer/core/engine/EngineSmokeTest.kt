package com.bittool.signer.core.engine

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bittool.signer.core.engine.internal.EngineBundle
import com.bittool.signer.core.engine.internal.EngineThread
import com.bittool.signer.core.engine.internal.NativeQuickJs
import com.bittool.signer.core.engine.internal.QuickJsContextHost
import com.bittool.signer.core.engine.internal.QuickJsSignerEngine
import com.bittool.signer.core.model.json.bool
import com.bittool.signer.core.model.json.int
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.str
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled engine runs on the device and reproduces the BIP84/BIP44 reference vectors; its runtimes cannot compile
 * source or reach a network object, and its entropy is live (QA audit A08: the iOS SecurityTests bridge case and the
 * Taproot V-NATIVE-2 vector of engine-contract.md §11.2).
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    private val engine = EngineModule.create(ApplicationProvider.getApplicationContext(), allowTestOperations = true)

    // abandon ×11 about, empty passphrase (BIP39 reference seed).
    private val seed = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"

    private fun log(message: String) = Log.i("EngineSmokeTest", message)

    @Test
    fun derivesReferenceAddresses() = runBlocking {
        var t = SystemClock.elapsedRealtime()
        engine.prewarm()
        log("prewarm (verify + compile): ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        engine.run(EngineOperation.Capabilities)
        log("fresh runtime + capabilities: ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        val index = engine.run(EngineOperation.DerivePublicIndex, JsonObject(mapOf("seed" to JsonPrimitive(seed))))
        log("derivePublicIndex: ${SystemClock.elapsedRealtime() - t} ms")
        assertEquals("73c5da0a", index["masterFingerprint"]!!.jsonPrimitive.content)

        suspend fun first(chain: String): String {
            val r = engine.run(
                EngineOperation.DerivePublicAddresses,
                JsonObject(mapOf("publicIndex" to index, "chain" to JsonPrimitive(chain), "start" to JsonPrimitive(0), "count" to JsonPrimitive(1))),
            )
            return r["addresses"]!!.jsonArray[0].jsonObject["address"]!!.jsonPrimitive.content
        }
        t = SystemClock.elapsedRealtime()
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", first("bitcoin"))
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", first("evm"))
        assertEquals("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", first("tron"))
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", first("solana"))
        log("4 warm address derivations: ${SystemClock.elapsedRealtime() - t} ms")

        val m = engine.run(EngineOperation.Mnemonic, JsonObject(mapOf("entropy" to JsonPrimitive("00000000000000000000000000000000"))))
        assertTrue(m["mnemonic"]!!.jsonPrimitive.content.endsWith("about"))

        val digest = engine.bundleDigest()
        log("engine ${digest.sha256} ${digest.bytes} bytes, QuickJS ${digest.quickJsVersion}")
        assertEquals("6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077", digest.sha256)
    }

    @Test
    fun nonAsciiTextRoundTripsThroughTheEngine() = runBlocking {
        // An EIP-191 personal message with supplementary characters: the review must show them unchanged.
        val text = "签名 ✓ 👩‍👩‍👧 \u0000end"
        val hex = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val request = JsonObject(
            mapOf(
                "chain" to JsonPrimitive("evm"),
                "kind" to JsonPrimitive("personal"),
                "path" to JsonPrimitive("m/44'/60'/0'/0/0"),
                "address" to JsonPrimitive("0x9858EfFD232B4033E47d90003D41EC34EcaEda94"),
                "message" to JsonPrimitive("0x$hex"),
            ),
        )
        val review = engine.run(EngineOperation.Review, JsonObject(mapOf("request" to request)))
        assertTrue(review.toString().contains("签名") || review.toString().contains(hex))
    }

    @Test
    fun runtimesHaveNoEvalAndNoNetwork() = runBlocking {
        // QA audit A08 (DECISIONS §5, engine-contract §0.2): code running in an engine runtime, after the real bundle,
        // can neither compile source (eval, Function and its async/generator siblings) nor reach a network object.
        assertTrue(engine.run(EngineOperation.Capabilities).isNotEmpty())
        val report = withContext(EngineThread.dispatcher) {
            QuickJsContextHost.createProbed(bundle(), probe()).use { reportOf(it.call(probeRequest("isolation"))) }
        }
        assertEquals(true, report.bool("engineLoaded"), "the probe ran after the real engine")
        for (name in listOf("eval", "Function", "functionConstructor", "asyncFunction", "generatorFunction")) {
            val refusal = report.str(name)
            assertNotNull(refusal, "$name compiled source in an engine runtime")
            assertTrue(refusal.contains("eval is not supported"), "$name: $refusal")
        }
        for (name in listOf("fetch", "XMLHttpRequest", "WebSocket")) assertEquals("undefined", report.str(name), name)
        assertEquals(true, report.bool("nativeRandomRemoved"), "the bootstrap removes the raw entropy function")

        // Control: the same probe in a runtime that keeps the parser (what JS_NewContext would give engine runtimes)
        // does compile source, so the checks above would catch that regression.
        val control = withContext(EngineThread.dispatcher) {
            val handle = NativeQuickJs.newRuntime(QuickJsContextHost.MEMORY_LIMIT_BYTES, QuickJsContextHost.MAX_STACK_BYTES, compiler = true)
            try {
                val bundle = bundle()
                NativeQuickJs.evalBytecode(handle, bundle.bootstrap)
                NativeQuickJs.evalBytecode(handle, bundle.engine)
                NativeQuickJs.evalBytecode(handle, probe())
                assertTrue(NativeQuickJs.bindDispatch(handle))
                reportOf(NativeQuickJs.dispatch(handle, probeRequest("control")))
            } finally {
                NativeQuickJs.freeRuntime(handle)
            }
        }
        assertNull(control.str("eval"))
        assertNull(control.str("Function"))
    }

    @Test
    fun javascriptBridgeHasFreshScopeNativeEntropyAndNoNetwork() = runBlocking {
        // iOS SecurityTests.javascriptBridgeHasFreshScopeNativeEntropyAndNoNetwork, through the production host: each
        // operation gets a fresh runtime, entropy comes from the native CSPRNG, request text is data, never code.
        val probed = QuickJsSignerEngine(context, allowTestOperations = true, createHost = { QuickJsContextHost.createProbed(it, probe()) })
        val dangerousText = "\"; globalThis.invocations = 99; //"
        val parameters = JsonObject(mapOf("text" to JsonPrimitive(dangerousText)))
        val first = probed.run(EngineOperation.Review, parameters)
        val second = probed.run(EngineOperation.Review, parameters)
        assertEquals(1, first.int("invocations"))
        assertEquals(1, second.int("invocations"))
        assertEquals(dangerousText, first.str("echo"))
        assertEquals("undefined", first.str("fetch"))
        assertEquals("undefined", first.str("XMLHttpRequest"))
        val firstRandom = first.str("random")!!
        assertNotEquals(firstRandom, second.str("random"))
        assertNotEquals(List(32) { "0" }.joinToString(","), firstRandom)

        // The warm public runtime is the one exception: it is reused until released.
        assertEquals(1, probed.run(EngineOperation.DerivePublicAddresses).int("invocations"))
        assertEquals(2, probed.run(EngineOperation.DerivePublicAddresses).int("invocations"))
        probed.releaseWarmContext()
        assertEquals(1, probed.run(EngineOperation.DerivePublicAddresses).int("invocations"))
    }

    @Test
    fun signingTheSameTaprootPsbtTwiceGivesTwoDifferentSignatures() = runBlocking {
        // engine-contract.md §0.4 / §11.2 V-NATIVE-2 (DECISIONS §9.6): BIP340 signing draws 32 bytes of auxiliary
        // randomness from crypto.getRandomValues, so two signatures of the same Taproot input differ, and only there.
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("vectors/native-vectors.json").use { it.readBytes() }
        assertEquals(NATIVE_VECTORS_SHA256, EngineBundle.sha256Hex(fixture))
        val vectors = Json.parseToJsonElement(fixture.decodeToString()).jsonObject
        val vector = vectors["vectors"]!!.jsonArray[2].jsonObject
        val request = vector["request"]!!.jsonObject
        val expected = vector["result"]!!.jsonObject
        assertNotNull(tapKeySignature(expected.str("psbt")!!), "vector 2 is the Taproot key-path vector")
        val review = engine.run(EngineOperation.Review, JsonObject(mapOf("request" to request)))
        assertEquals(expected.str("requestHash"), review.str("requestHash"))

        // The debug `sign` operation with the vector's phrase: every field as the vector except `psbt`.
        val sign = JsonObject(mapOf("request" to request, "mnemonic" to vectors["mnemonic"]!!))
        val signed = List(2) { engine.run(EngineOperation.Sign, sign) }
        for (result in signed) assertEquals(expected - "psbt", result - "psbt")
        assertTwoDistinctSignaturesOfOneTransaction(signed.map { it.str("psbt")!! }, expected.str("psbt")!!)

        // The production path: plan with the reference wallet, then signPlanTask with the native seed, twice.
        val index = engine.run(EngineOperation.DerivePublicIndex, JsonObject(mapOf("seed" to JsonPrimitive(seed))))
        val wallet = JsonObject(mapOf("localWalletId" to JsonPrimitive("00112233-4455-6677-8899-aabbccddeeff"), "publicIndex" to index))
        val plan = engine.run(EngineOperation.ResolveSigningPlan, JsonObject(mapOf("request" to request, "wallets" to JsonArray(listOf(wallet)))))
        assertEquals("ready", plan.str("status"))
        val task = JsonObject(
            mapOf(
                "request" to request,
                "plan" to plan,
                "taskId" to JsonPrimitive(plan.objects("tasks")!!.single().str("id")!!),
                "seed" to JsonPrimitive(seed),
                "riskAcknowledged" to JsonPrimitive(review.str("decodeStatus") != "fullyDecoded"),
            ),
        )
        val tasks = List(2) { engine.run(EngineOperation.SignPlanTask, task) }
        assertEquals(tasks[0] - "psbt", tasks[1] - "psbt")
        assertEquals(expected.str("requestHash"), tasks[0].str("requestHash"))
        assertTwoDistinctSignaturesOfOneTransaction(tasks.map { it.str("psbt")!! }, expected.str("psbt")!!)
    }

    private fun assertTwoDistinctSignaturesOfOneTransaction(psbts: List<String>, vectorPsbt: String) {
        val signatures = psbts.map { assertNotNull(tapKeySignature(it), "a Taproot key-path signature") }
        for (signature in signatures) assertEquals(64, signature.size, "BIP340 with SIGHASH_DEFAULT")
        assertFalse(signatures[0].contentEquals(signatures[1]), "the auxiliary randomness is live")
        // Nothing but the signature differs, from each other or from the vector.
        val unsigned = withoutTapKeySignatures(vectorPsbt)
        for (psbt in psbts) assertEquals(unsigned, withoutTapKeySignatures(psbt))
    }

    // region probes and PSBT reading

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var loadedBundle: EngineBundle? = null
    private var probeBytecode: ByteArray? = null

    /** On the engine thread. */
    private fun bundle(): EngineBundle = loadedBundle ?: EngineBundle.load(context).also { loadedBundle = it }

    /** On the engine thread. */
    private fun probe(): ByteArray =
        probeBytecode ?: EngineBundle.compile("isolation-probe.js" to PROBE.toByteArray(Charsets.UTF_8)).single().also { probeBytecode = it }

    private fun probeRequest(text: String) = """{"operation":"probe","text":"$text"}""".toByteArray(Charsets.UTF_8)

    private fun reportOf(response: ByteArray): JsonObject =
        Json.parseToJsonElement(response.decodeToString()).jsonObject["result"]!!.jsonObject

    /** The maps of a serialized PSBT (global, then inputs, then outputs), each a list of (key, value) records. */
    private fun psbtMaps(base64: String): List<List<Pair<List<Byte>, List<Byte>>>> {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        assertEquals("70736274ff", bytes.copyOf(5).joinToString("") { "%02x".format(it) }, "PSBT magic")
        var at = 5
        fun compactSize(): Int {
            val first = bytes[at++].toInt() and 0xFF
            val width = when (first) {
                0xFD -> 2
                0xFE -> 4
                0xFF -> 8
                else -> return first
            }
            var value = 0L
            repeat(width) { value = value or ((bytes[at++].toLong() and 0xFF) shl (8 * it)) }
            return value.toInt()
        }
        fun take(n: Int): List<Byte> = bytes.copyOfRange(at, at + n).toList().also { at += n }
        val maps = mutableListOf<List<Pair<List<Byte>, List<Byte>>>>()
        while (at < bytes.size) {
            val records = mutableListOf<Pair<List<Byte>, List<Byte>>>()
            while (true) {
                val keyLength = compactSize()
                if (keyLength == 0) break
                val key = take(keyLength)
                records += key to take(compactSize())
            }
            maps += records
        }
        return maps
    }

    /** PSBT_IN_TAP_KEY_SIG (0x13) of the first input, or null. */
    private fun tapKeySignature(psbt: String): ByteArray? =
        psbtMaps(psbt)[1].firstOrNull { (key, _) -> key == listOf(TAP_KEY_SIG) }?.second?.toByteArray()

    private fun withoutTapKeySignatures(psbt: String) = psbtMaps(psbt).map { map -> map.filterNot { (key, _) -> key == listOf(TAP_KEY_SIG) } }

    // endregion

    private companion object {
        /** engine-contract.md §11.1: `engine/test/native-vectors.json` of the iOS repository, byte for byte. */
        const val NATIVE_VECTORS_SHA256 = "58fbf7749f6b7b76849dfce81f3050c3d9a69376989dda571aaf58314bcf8aef"
        const val TAP_KEY_SIG: Byte = 0x13

        /**
         * Evaluated after the real engine in a runtime of the engine kind: keeps the engine, then replaces the global
         * the host binds `dispatch` from with a reporter (iOS uses the same kind of stand-in engine source).
         */
        const val PROBE = """
(function () {
    'use strict';
    var engine = BitToolSignerEngine;
    function refusal(attempt) {
        try { attempt(); return null; } catch (e) { return String(e); }
    }
    BitToolSignerEngine = {dispatch: function (json) {
        var request = JSON.parse(json);
        globalThis.invocations = (globalThis.invocations || 0) + 1;
        var bytes = new Uint8Array(32);
        crypto.getRandomValues(bytes);
        return JSON.stringify({ok: true, result: {
            invocations: globalThis.invocations,
            random: Array.prototype.join.call(bytes, ','),
            echo: request.text === undefined ? null : request.text,
            engineLoaded: typeof engine.dispatch === 'function',
            nativeRandomRemoved: typeof globalThis.__bitSignerRandom === 'undefined',
            fetch: typeof fetch,
            XMLHttpRequest: typeof XMLHttpRequest,
            WebSocket: typeof WebSocket,
            eval: refusal(function () { return (0, eval)('1'); }),
            Function: refusal(function () { return new Function('return 1')(); }),
            functionConstructor: refusal(function () { return (function () {}).constructor('return 1')(); }),
            asyncFunction: refusal(function () { return Object.getPrototypeOf(async function () {}).constructor('return 1'); }),
            generatorFunction: refusal(function () { return Object.getPrototypeOf(function* () {}).constructor('yield 1'); })
        }});
    }};
})();
"""
    }
}
