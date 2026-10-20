package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.data.doubles.HmacBip39Seed
import com.bittool.signer.core.data.doubles.JvmSecureRandomSource
import com.bittool.signer.core.data.doubles.PublicFixtures
import com.bittool.signer.core.data.doubles.PublicFixtures.PHRASE
import com.bittool.signer.core.data.doubles.PublicFixtures.SIGNER
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.engine.EngineCalls
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.int
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.json.strings
import com.bittool.signer.core.model.signing.PlanCandidate
import com.bittool.signer.core.model.signing.SigningPlan
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.model.util.Hex
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of iOS `BitToolSignerTests.swift` (`EngineIntegrationTests`, 4 cases) plus the store-level engine vectors of
 * SecurityTests (zero-entropy mnemonic) and engine-contract.md §11.2 (V-PLAN goldens, a two-cosigner PSBT signed through
 * the store). Seeds come from the native seed path, as in production (the engine never receives a phrase).
 */
@RunWith(AndroidJUnit4::class)
class SignerStoreEngineTest {
    private val engine get() = DeviceEngine.real
    private val calls get() = EngineCalls(engine)
    private val referenceWallet = WalletId.parse("00112233-4455-6677-8899-aabbccddeeff")!!

    private fun seed(phrase: String, passphrase: String = ""): CharArray = phrase.asSecret { words -> passphrase.asSecret { HmacBip39Seed().hex(words, it) } }

    private suspend fun index(phrase: String = PHRASE, passphrase: String = ""): WalletPublicMetadata {
        val seed = seed(phrase, passphrase)
        return WalletPublicMetadata.parse(calls.derivePublicIndex(seed))
    }

    private fun params(vararg entries: Pair<String, Any>): JsonObject = JsonObject(
        entries.associate { (key, value) ->
            key to when (value) {
                is JsonObject, is JsonArray, is JsonPrimitive -> value as kotlinx.serialization.json.JsonElement
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> error("unsupported $value")
            }
        },
    )

    private suspend fun resolve(request: JsonObject): Pair<JsonObject, String> {
        val plan = calls.resolveSigningPlan(request, listOf(PlanCandidate(referenceWallet, index(), PublicMetadataStatus.Ready)))
        assertEquals("ready", plan.str("status"))
        return plan to assertNotNull(plan.objects("tasks")?.firstOrNull()?.str("id"))
    }

    /** The explicit public output encoder (debug `encodeQR`), as iOS `checkPublicFixtureExport`. */
    private suspend fun checkPublicFixtureExport(chain: String, result: JsonObject) {
        val type = mapOf("bitcoin" to "crypto-psbt", "evm" to "eth-signature", "solana" to "sol-signature", "tron" to "tron-signature").getValue(chain)
        val payload = assertNotNull(if (chain == "bitcoin") result.str("psbt") else result.str("qrSignature") ?: result.str("signature"))
        val encoded = engine.run(
            EngineOperation.EncodeQR,
            params("type" to type, "payload" to payload, "encoding" to (if (chain == "bitcoin") "base64" else "hex"), "requestId" to "8f235e99-e6e8-4044-a51d-51ec3ec942b0"),
        )
        val frames = assertNotNull(encoded.strings("frames"))
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.lowercase().startsWith("ur:$type/") }, "$frames")
    }

    @Test
    fun bundledEngineDerivesKnownFourChainAccountsAndPublicPairing() = runBlocking<Unit> {
        val generated = calls.mnemonic("00".repeat(16).toCharArray())
        assertEquals(PHRASE, generated.str("mnemonic"))
        val derived = engine.run(EngineOperation.DeriveAccounts, params("mnemonic" to PHRASE))
        val accounts = assertNotNull(derived.objects("accounts"))
        assertEquals(4, accounts.size)
        fun address(chain: String) = accounts.first { it.str("chain") == chain }.str("address")
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", address("bitcoin"))
        assertEquals(SIGNER, address("evm"))
        assertEquals("TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH", address("tron"))
        assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", address("solana"))
        val index = index()
        assertEquals(PublicFixtures.WALLET_KEY_ID, index.walletKeyId)
        assertEquals(PublicFixtures.FINGERPRINT, index.masterFingerprint)
        val pairing = calls.createPublicPairing(index, Chain.Evm, "standard", "wallet", 0)
        assertTrue(pairing.strings("frames")!!.first().lowercase().startsWith("ur:crypto-hdkey/"))
    }

    @Test
    fun fixedEvmPlanSignsAndExportsWithoutNetwork() = runBlocking<Unit> {
        val request = PublicFixtures.evmRequest(transport = false)
        val review = calls.review(request)
        assertEquals("true", review["canSign"].toString())
        val hash = assertNotNull(review.str("requestHash"))
        val (plan, taskId) = resolve(request)
        val signed = calls.signPlanTask(request, plan, taskId, seed(PHRASE), riskAcknowledged = false, previousResult = null)
        assertEquals(hash, signed.str("requestHash"))
        assertEquals(SIGNER, signed.str("address"))
        assertTrue(signed.str("serializedTransaction")!!.startsWith("0x02"))
        assertEquals(130, signed.str("signature")?.length)
        // This fixture is an internal request with no scanned public transport: there is no private-JSON fallback.
        assertFailsWith<EngineException> { calls.encodeResponse(request, signed) }
        checkPublicFixtureExport("evm", signed)
        val altered = PublicFixtures.evmRequest(nonce = 1, transport = false)
        assertFailsWith<EngineException> { calls.signPlanTask(altered, plan, taskId, seed(PHRASE), riskAcknowledged = false, previousResult = null) }
    }

    @Test
    fun malformedQrIsRejectedAndOpaqueContractRequiresPerRequestRiskConsent() = runBlocking<Unit> {
        assertFailsWith<EngineException> { calls.decodeQr(listOf("ur:eth-sign-request/invalid")) }
        val request = PublicFixtures.evmRequest(data = "0xdeadbeef", transport = false)
        val review = calls.review(request)
        assertEquals("true", review["canSign"].toString())
        assertTrue(review.str("decodeStatus") in setOf("partiallyDecoded", "opaque"))
        assertTrue(review.strings("warnings")!!.isNotEmpty())
        val (plan, taskId) = resolve(request)
        assertFailsWith<EngineException> { calls.signPlanTask(request, plan, taskId, seed(PHRASE), riskAcknowledged = false, previousResult = null) }
        val accepted = calls.signPlanTask(request, plan, taskId, seed(PHRASE), riskAcknowledged = true, previousResult = null)
        assertEquals(review.str("requestHash"), accepted.str("requestHash"))
        assertEquals(130, accepted.str("signature")?.length)
    }

    @Test
    fun nativeRuntimeResolvesSolanaTronAndTwoWalletBitcoinPlans() = runBlocking<Unit> {
        // engine/test/fixtures/public-plan-vectors.json; both recovery phrases are published BIP39 fixtures.
        val fixture = parseJsonObject(javaClass.getResourceAsStream("/PublicPlanVectors.json")!!.use { it.readBytes() })!!
        assertEquals("true", fixture["publicFixture"].toString())
        val wallets = fixture.objects("mnemonics")!!
        val candidates = wallets.map { wallet ->
            PlanCandidate(WalletId.parse(wallet.str("localWalletId"))!!, index(wallet.str("mnemonic")!!), PublicMetadataStatus.Ready)
        }
        // engine-contract.md §11.2 V-PLAN goldens.
        val goldens = mapOf(
            "EVM registered index 8" to ("7a11854f3ee455201faf08146e3aa13a6f92ddc3ea49b4b6d28836044fe8a565" to "b4921fdef66a27919ab6db876ea248156c2a2dad545184bf393a524286b1aed2"),
            "SOL standard" to ("223a84f032be3def8cbe881d6d58186ea1bf477ccce5b566194ded52603217ec" to "f9c9316eca16d46567a4f2d76af76b48b732c4be1196b9146f31fe24e0674b47"),
            "SOL trust" to ("9a9a1e3c9ffd89dcec9218f5363a3a68b7f769d26e91967b367208bb72a03b75" to "b885b77e2840bfc180cb024e96ea6146cbf67f888c46aaab3b4c36580f053106"),
            "SOL legacy" to ("f5ed061241afbe92312bfdbff4006f7d023859c6e3cec9dadee03d71f0e57ec9" to "1fb9e1a7b101f9c94224832caf9359436d2062b77daefc80e99bbf8804f0713b"),
            "TRON index 99 without path" to ("ca35f003626fcd9e66cba1d7e895ef2656056864b62ad64095f678ab88e07717" to "74e086e32c7ee10de8214e7e23edf590c16e678eea98b204eb161985bfae8156"),
            "BTC two local BIP48 cosigners without derivations" to ("8a0bdb3b85b9780564fe034768a3ec379df4abede94d47532fec5d76f6d33c00" to "8bf240f06b7c8ecaeb678f1c967f620a390733a1715332ec129c364e6ea59bef"),
        )
        val signedNames = setOf("SOL legacy", "TRON index 99 without path", "BTC two local BIP48 cosigners without derivations")
        val vectors = fixture.objects("vectors")!!
        assertEquals(goldens.keys, vectors.map { it.str("name") }.toSet())
        for (vector in vectors) {
            val name = vector.str("name")!!
            val request = vector["request"] as JsonObject
            val expected = vector["expected"] as JsonObject
            val review = calls.review(request)
            val requestHash = assertNotNull(review.str("requestHash"))
            val planObject = calls.resolveSigningPlan(request, candidates)
            val plan = SigningPlan.parse(planObject, requestHash)
            assertEquals(goldens.getValue(name), requestHash to plan.planHash, name)
            assertEquals(expected.strings("taskWalletIds"), plan.tasks.map { it.walletId.toString() }, name)
            assertEquals((expected["taskPaths"] as JsonArray).map { (it as JsonArray).map { p -> (p as JsonPrimitive).content } }, plan.tasks.map { it.paths }, name)
            if (name !in signedNames) continue
            var previous: JsonObject? = null
            val completed = mutableListOf<String>()
            for (task in plan.tasks) {
                val mnemonic = wallets.first { WalletId.parse(it.str("localWalletId")) == task.walletId }.str("mnemonic")!!
                val result = calls.signPlanTask(request, planObject, task.id, seed(mnemonic), riskAcknowledged = false, previousResult = previous)
                completed += task.id
                assertEquals(plan.requestHash, result.str("requestHash"))
                assertEquals(plan.planHash, result.str("planHash"))
                assertEquals(completed, result.strings("completedTaskIds"))
                if (task.chain == Chain.Bitcoin) {
                    assertEquals(1, result.int("signedInputs"))
                    assertNotEquals(previous?.str("psbt") ?: request.str("psbt"), result.str("psbt"))
                } else {
                    assertEquals(if (task.chain == Chain.Solana) 128 else 130, result.str("signature")?.length, name)
                    assertTrue(result.str("address") in task.addresses, name)
                    if (task.chain == Chain.Solana) assertEquals(task.signerSlot, result.int("signerIndex"))
                }
                previous = result
            }
            checkPublicFixtureExport(request.str("chain")!!, assertNotNull(previous))
        }
    }

    @Test
    fun aZeroEntropyPhraseIsTheBip39VectorThroughTheStore() {
        // SecurityTests: entropy 00×16 → "abandon ×11 about", here through SignerStore.generateMnemonic.
        val zero = object : SecureRandomSource by JvmSecureRandomSource() {
            override fun entropyHex(wordCount: Int): CharArray = CharArray(wordCount / 3 * 8) { '0' }
            override fun asKotlinRandom(): Random = Random(0)
        }
        DeviceStore.run(random = zero) {
            store.reload()
            assertEquals(PHRASE, store.generateMnemonic(12).use { String(it.chars()) })
            assertEquals(
                "abandon ".repeat(23) + "art",
                store.generateMnemonic(24).use { String(it.chars()) },
            )
        }
    }

    @Test
    fun twoLocalCosignersSignOnePsbtInTurnThroughTheStore() {
        // The BIP48 two-cosigner vector, pasted as Base64 text: one task per wallet in plan order, each with its own
        // password, the second receiving the first result; only the final task publishes a response.
        val fixture = parseJsonObject(javaClass.getResourceAsStream("/PublicPlanVectors.json")!!.use { it.readBytes() })!!
        val vector = fixture.objects("vectors")!!.first { it.str("name") == "BTC two local BIP48 cosigners without derivations" }
        val psbt = (vector["request"] as JsonObject).str("psbt")!!
        val phrases = fixture.objects("mnemonics")!!.map { it.str("mnemonic")!! }
        DeviceStore.run {
            store.reload()
            val first = createWallet("Cosigner one", phrase = phrases[0], password = "Cosigner-One-1")
            val second = createWallet("Cosigner two", phrase = phrases[1], password = "Cosigner-Two-2")
            val passwords = mapOf(first.id to "Cosigner-One-1", second.id to "Cosigner-Two-2")
            store.importPayload(psbt)
            val plan = assertNotNull(state.plan, "${state.matchingIssue}")
            assertEquals(2, plan.tasks.size)
            assertEquals(setOf(first.id, second.id), plan.tasks.map { it.walletId }.toSet())
            for ((offset, task) in plan.tasks.withIndex()) {
                assertEquals(task.id, state.pendingTasks.first().id)
                sign(task.id, password = passwords.getValue(task.walletId))
                assertEquals(offset == plan.tasks.lastIndex, state.hasSignedResponse)
            }
            assertEquals(1, state.signatureFrames.size, "A short signed PSBT goes back as one static text frame.")
            assertTrue(state.signatureText.startsWith("cHNidP"), "The response keeps the request's Base64 PSBT encoding.")
            assertNotEquals(psbt, state.signatureText)
            assertEquals(listOf(SigningReceipt.RESULT_SIGNATURE, SigningReceipt.RESULT_PARTIAL), state.receipts.map { it.resultType })
            assertEquals(plan.tasks.reversed().map { it.walletId.toString() }, state.receipts.map { it.walletId })
            assertEquals(state.signatureText, state.receipts.first().responseText)
            assertEquals(null, state.receipts.last().responseText)
            assertTrue(Hex.isHash64(state.receipts.first().requestHash))
        }
    }
}
