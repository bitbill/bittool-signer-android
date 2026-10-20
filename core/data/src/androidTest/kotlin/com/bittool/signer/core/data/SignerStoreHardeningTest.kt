package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.data.doubles.HmacBip39Seed
import com.bittool.signer.core.data.doubles.HmacPublicDataKey
import com.bittool.signer.core.data.doubles.PublicFixtures
import com.bittool.signer.core.data.doubles.PublicFixtures.PHRASE
import com.bittool.signer.core.data.doubles.PublicFixtures.SIGNER
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.data.wallet.FilePublicWalletRepository
import com.bittool.signer.core.engine.EngineCalls
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineModule
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.bool
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.json.strings
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `:core:data` cases of iOS `HardeningTests.swift` (public files authenticated, incremental scan session, warm
 * context) on the device with the bundled engine, plus the same paths through the store.
 */
@RunWith(AndroidJUnit4::class)
class SignerStoreHardeningTest {
    private val engine get() = DeviceEngine.real
    private val calls get() = EngineCalls(engine)

    private suspend fun referenceIndex(): JsonObject {
        val seed = PHRASE.asSecret { words -> "".asSecret { HmacBip39Seed().hex(words, it) } }
        return calls.derivePublicIndex(seed)
    }

    @Test
    fun publicWalletFilesAreAuthenticated() = runBlocking<Unit> {
        val dir = File(DeviceEngine.context.cacheDir, "public-" + UUID.randomUUID())
        try {
            val repository = FilePublicWalletRepository(dir, Dispatchers.IO)
            val index = WalletPublicMetadata.parse(referenceIndex())
            val id = WalletId.random()
            val key = HmacPublicDataKey()
            repository.save(index, id, "Public", key)
            val good = repository.snapshot(id, key)
            assertEquals(PublicMetadataStatus.Ready, good.status)
            assertEquals("Public", good.displayName)
            assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, HmacPublicDataKey()).status)
            assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, null).status)
            // Swap a displayed name inside the body: the MAC no longer verifies.
            val file = repository.file(id)
            val envelope = parseJsonObject(file.readBytes())!!
            val body = Base64.getDecoder().decode(envelope.str("body")).decodeToString()
            val tampered = body.replace("\"displayName\":\"Public\"", "\"displayName\":\"Pwned\"")
            file.writeText(JsonObject(envelope + ("body" to JsonPrimitive(Base64.getEncoder().encodeToString(tampered.encodeToByteArray())))).canonicalJson())
            assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status)
            assertEquals(PublicMetadataStatus.NeedsCompletion, repository.snapshot(WalletId.random(), key).status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun incrementalScanSessionAssemblesAnimatedRequests() = runBlocking<Unit> {
        val index = referenceIndex()
        val pairing = engine.run(
            EngineOperation.CreatePublicPairing,
            JsonObject(
                mapOf(
                    "publicIndex" to index, "chain" to JsonPrimitive("bitcoin"), "profile" to JsonPrimitive("native"),
                    "outputMode" to JsonPrimitive("accountXpub"), "fragmentLength" to JsonPrimitive(40),
                ),
            ),
        )
        val frames = assertNotNull(pairing.strings("frames"))
        assertTrue(frames.size > 1)
        // Pairing exports are not signing requests: the session must reject them clearly.
        engine.beginScanSession()
        assertFailsWith<EngineException> { engine.scan(frames[0]) }
        engine.endScanSession()
        val ended = assertFailsWith<EngineException> { engine.scan(frames[0]) }
        assertEquals(EngineException.SCAN, ended.code)

        // A real PSBT split into a fountain-coded animated QR is reassembled frame by frame.
        val (psbt, parts) = animatedPsbt()
        assertTrue(parts.size > 3)
        engine.beginScanSession()
        var result = JsonObject(emptyMap())
        for (part in parts + parts) {
            result = engine.scan(part)
            if (result.bool("complete") == true) break
        }
        assertEquals(true, result.bool("complete"))
        assertEquals(psbt, result.obj("request")?.str("psbt"))
        engine.endScanSession()
    }

    @Test
    fun theStoreAssemblesAnAnimatedRequestFrameByFrame() {
        val (psbt, parts) = runBlocking { animatedPsbt() }
        DeviceStore.run {
            store.reload()
            createWallet("Reference")
            store.beginScan()
            var progress = 0.0
            for (part in parts + parts) {
                val value = store.receive(part)
                assertTrue(value >= 0.0 && value <= 1.0)
                progress = maxOf(progress, value)
                if (state.review != null) break
            }
            val review = assertNotNull(state.review, "The request is complete and reviewed.")
            assertEquals(Chain.Bitcoin, review.chain)
            assertEquals(1.0, progress)
            assertEquals(1, engine.invocationCount("scanBegin"))
            assertTrue(engine.invocationCount("scanAdd") >= parts.size)
            assertEquals(0, engine.invocationCount(EngineOperation.DecodeQR), "The incremental session is used, not batch decoding.")
            assertTrue(review.fields.isNotEmpty())
            assertTrue(psbt.startsWith("cHNidP"))
            store.endScan()
        }
    }

    @Test
    fun warmPublicContextReturnsStableResults() = runBlocking<Unit> {
        val index = WalletPublicMetadata.parse(referenceIndex())
        val first = calls.derivePublicAddresses(index, Chain.Evm, "standard", 0, 3)
        val second = calls.derivePublicAddresses(index, Chain.Evm, "standard", 0, 3)
        val addresses = first.objects("addresses")?.map { it.str("address") }
        assertEquals(SIGNER, addresses?.first())
        assertEquals(addresses, second.objects("addresses")?.map { it.str("address") })
        // A release engine refuses the debug-only operations; the store translates that host message.
        val release = EngineModule.create(DeviceEngine.context, allowTestOperations = false)
        val refused = assertFailsWith<EngineException> { release.run(EngineOperation.Sign, JsonObject(emptyMap())) }
        assertEquals(EngineException.UNSUPPORTED_OPERATION, refused.code)
        val translated = EngineHostErrors.translate(refused)
        assertEquals(UiText.Resource(R.string.data_engine_unsupported_operation), (translated as EngineHostException).text)
    }

    @Test
    fun storeAddressesAreStableAcrossASuspendThatDropsTheWarmRuntime() = DeviceStore.run {
        val wallet = makeStore()
        val before = store.addresses(wallet.id, Chain.Bitcoin, "native", 0, 2).map { it.address }
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", before.first())
        store.suspendSession()
        assertEquals(before, store.addresses(wallet.id, Chain.Bitcoin, "native", 0, 2).map { it.address })
        val solana = store.addresses(wallet.id, Chain.Solana, "", 0, 3)
        assertEquals(3, solana.size, "Solana returns its three presets at once.")
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", solana.first().address)
        val tron = store.addresses(wallet.id, Chain.Tron, "standard", 99, 1).single()
        assertEquals("TTTFe9haCY6CACG9iTM8uyL89pFEPy4ctW", tron.address)
        assertEquals(PublicFixtures.FINGERPRINT, state.publicMetadata(wallet.id)?.masterFingerprint)
        assertNull(store.preparedConnect(wallet.id))
    }

    /** The Bitcoin plan vector's PSBT as an animated `crypto-psbt` (debug `encodeQR`, fragment 60). */
    private suspend fun animatedPsbt(): Pair<String, List<String>> {
        val fixture = parseJsonObject(javaClass.getResourceAsStream("/PublicPlanVectors.json")!!.use { it.readBytes() })!!
        val vector = fixture.objects("vectors")!!.first { (it["request"] as JsonObject).str("chain") == "bitcoin" }
        val psbt = (vector["request"] as JsonObject).str("psbt")!!
        val animated = engine.run(
            EngineOperation.EncodeQR,
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("crypto-psbt"), "payload" to JsonPrimitive(psbt),
                    "encoding" to JsonPrimitive("base64"), "fragmentLength" to JsonPrimitive(60),
                ),
            ),
        )
        return psbt to animated.strings("frames")!!
    }
}
