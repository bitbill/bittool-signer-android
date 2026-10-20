package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.DeviceStore.Companion.PASSWORD
import com.bittool.signer.core.data.doubles.PublicFixtures
import com.bittool.signer.core.data.doubles.PublicFixtures.ALTERNATE_PASSPHRASE
import com.bittool.signer.core.data.doubles.PublicFixtures.PHRASE
import com.bittool.signer.core.data.doubles.PublicFixtures.SIGNER
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.arr
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.signing.MatchingIssue
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of iOS `BitToolSignerTests/SessionTests.swift` (15 cases) over the bundled engine. Gates expose races
 * deterministically. Public BIP39 fixtures and synthetic offline transactions only.
 */
@RunWith(AndroidJUnit4::class)
class SignerStoreSessionTest {
    private fun request(nonce: Int = 0) = PublicFixtures.evmRequest(nonce)

    @Test
    fun oldEncryptedWalletMigratesWithoutRewritingItsSecret() = DeviceStore.run {
        val wallet = makeStore(complete = false)
        val before = vault.revision(wallet.id)
        assertEquals(PublicMetadataStatus.NeedsCompletion, state.metadataStatus(wallet.id))
        assertNull(state.publicMetadata(wallet.id))
        assertEquals(0, vault.unlockAttempts(wallet.id), "Loading never tries a password.")
        PASSWORD.asSecret { store.completeSetup(wallet.id, it) }
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id))
        assertEquals(before, vault.revision(wallet.id), "Repair never rewrites the encrypted wallet.")
        val index = assertNotNull(state.publicMetadata(wallet.id))
        assertEquals(6, index.raw.arr("bitcoinPublicNodes")?.size)
        assertEquals(100, index.raw.arr("tronAddressIndex")?.size)
        assertEquals(3, index.raw.arr("solanaPublicKeys")?.size)
        assertNull(index.raw["solanaAccounts"])
        assertEquals("1", index.raw["version"].toString())

        // Wallet-level pairing reads only this public index: the four BTC script xpubs, EVM, TRON and three Solana addresses.
        val pairing = store.walletPairing(wallet.id)
        assertTrue(pairing.frames.size >= 2, "The wallet-level QR is animated: ${pairing.frames.size} frame(s).")
        assertTrue(pairing.frames.all { it.startsWith("UR:BYTES/") })
        assertEquals(6, pairing.json.split("\"xpub\"").size - 1)
        assertEquals(3, pairing.json.split("\"address\"").size - 1)
        for (path in listOf("m/84'/0'/0'", "m/86'/0'/0'", "m/49'/0'/0'", "m/44'/0'/0'")) assertTrue(pairing.json.contains(path), "$path is exported")
        assertFalse(pairing.json.contains("m/48'"), "Multisig profiles stay out of the pairing.")
        assertTrue(pairing.json.contains("m/44'/501'/0'/0'") && !pairing.json.contains("m/44'/501'/1'/0'"))
        assertEquals(listOf("btc", "btc", "btc", "btc", "evm", "tron", "sol", "sol", "sol"), pairing.firstAddresses.map { it.chain })
        // Every line needs its own identity and its own address.
        assertEquals(pairing.firstAddresses.size, pairing.firstAddresses.map { it.id }.toSet().size)
        assertEquals(pairing.firstAddresses.size, pairing.firstAddresses.map { it.address }.toSet().size)
        assertEquals(pairing.firstAddresses.size, pairing.firstAddresses.map { it.path }.toSet().size)
        assertEquals(listOf("native", "taproot", "nested", "legacy"), pairing.firstAddresses.filter { it.chain == "btc" }.map { it.profile })
        assertEquals(listOf("standard", "trust", "legacy"), pairing.firstAddresses.filter { it.chain == "sol" }.map { it.profile })
        val bitcoin = pairing.firstAddresses.filter { it.chain == "btc" }.map { it.address }
        assertTrue(bitcoin[0].startsWith("bc1q") && bitcoin[1].startsWith("bc1p") && bitcoin[2].startsWith("3") && bitcoin[3].startsWith("1"), "$bitcoin")
        assertEquals(SIGNER, pairing.firstAddresses.first { it.chain == "evm" }.address)
        assertTrue(pairing.json.contains("\"protocol\" : \"bittool-signer-wallet\"") && pairing.json.contains("xpub") && !pairing.json.contains("xprv"))

        assertFailsWith<AppIssue> { WalletPublicMetadata.parse(JsonObject(index.raw + ("version" to JsonPrimitive(2)))) }
        val cache = sidecar(wallet.id).readText()
        val body = Base64.getDecoder().decode(parseJsonObject(cache)!!.str("body")).decodeToString()
        for (text in listOf(cache, body)) {
            assertFalse(text.contains(PASSWORD))
            assertFalse(text.contains("abandon"))
            assertFalse(text.contains("xprv"))
        }
        val reloaded = fixture.relaunch()
        reloaded.reload()
        assertEquals(index.walletKeyId, reloaded.state.value.publicMetadata(wallet.id)?.walletKeyId)
        assertEquals(wallet.id, reloaded.state.value.wallets.first().id)
    }

    @Test
    fun corruptPublicCacheDoesNotDamageOrDecryptTheWallet() = DeviceStore.run {
        val wallet = makeStore()
        val encrypted = vault.revision(wallet.id)
        val attempts = vault.unlockAttempts(wallet.id)
        sidecar(wallet.id).writeText("corrupt public fixture")
        store.reload()
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(wallet.id))
        assertNull(state.publicMetadata(wallet.id))
        assertEquals(encrypted, vault.revision(wallet.id))
        assertEquals(attempts, vault.unlockAttempts(wallet.id), "A damaged cache never triggers seed decryption.")
        PASSWORD.asSecret { store.completeSetup(wallet.id, it) }
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id))
    }

    @Test
    fun stableIdentitySurvivesRenamePasswordChangeAndRejectsDuplicateImport() = DeviceStore.run {
        val wallet = makeStore()
        val key = assertNotNull(state.publicMetadata(wallet.id)?.walletKeyId)
        assertEquals(PublicFixtures.WALLET_KEY_ID, key)
        val fingerprint = state.fingerprint(wallet.id)
        assertEquals("73C5DA0A", fingerprint, "The displayed identity is the BIP32 master fingerprint, as in pairing exports.")
        val encrypted = vault.revision(wallet.id)
        store.renameWallet(wallet.id, "Renamed public fixture")
        assertEquals("Renamed public fixture", state.displayName(wallet.id))
        assertEquals(fingerprint, state.fingerprint(wallet.id))
        assertEquals(encrypted, vault.revision(wallet.id))
        val duplicate = assertFailsWith<AppIssue>("The same effective seed must not create a duplicate wallet.") { createWallet("Duplicate") }
        assertTrue((duplicate.text as com.bittool.signer.core.model.UiText.Ios).english.contains("already belong"))
        assertEquals(1, state.wallets.size)
        val other = createWallet("Different public passphrase", passphrase = ALTERNATE_PASSPHRASE)
        assertNotEquals(key, state.publicMetadata(other.id)?.walletKeyId)
        assertEquals(2, state.wallets.size)
        PASSWORD.asSecret { current -> "Changed-Password2".asSecret { new -> store.changePassword(wallet.id, current, new) } }
        store.reload()
        assertEquals(key, state.publicMetadata(wallet.id)?.walletKeyId)
        assertEquals(fingerprint, state.fingerprint(wallet.id))
        assertNotEquals(fingerprint, state.fingerprint(other.id), "A different passphrase gives a different fingerprint.")
        "Changed-Password2".asSecret { vault.verifyPassword(wallet.id, it) }
    }

    @Test
    fun incompleteLegacyWalletDoesNotBlockImportAndDuplicatesAreFlaggedWhenCompleted() = DeviceStore.run {
        val original = makeStore(complete = false)
        assertTrue(state.hasIncompleteWallets)
        val imported = createWallet("Unknown legacy identity")
        assertEquals(2, state.wallets.size)
        assertEquals(PublicMetadataStatus.NeedsCompletion, state.metadataStatus(original.id))
        store.prepare(request())
        val previouslyMatched = assertNotNull(state.pendingTasks.firstOrNull())
        assertEquals(imported.id, previouslyMatched.walletId)
        PASSWORD.asSecret { store.completeSetup(original.id, it) }
        assertFalse(state.hasIncompleteWallets)
        assertEquals(setOf(original.id, imported.id), state.duplicateWalletIds)
        assertNull(state.plan)
        assertEquals(MatchingIssue.WalletsChanged, state.matchingIssue)
        assertFailsWith<AppIssue> { sign(previouslyMatched.id) }
        store.retryMatching()
        assertNull(state.plan)
        assertNotNull(state.matchingIssue)
        assertEquals(2, state.wallets.size)
    }

    @Test
    fun publicBrowsingDoesNotSelectASignerAndEachSignatureNeedsItsPassword() = DeviceStore.run {
        val wallet = makeStore()
        val other = createWallet("Other public wallet", passphrase = ALTERNATE_PASSPHRASE, password = "Other-Password2")
        val addresses = store.addresses(other.id, Chain.Evm, "standard", start = 8, count = 2)
        assertEquals(2, addresses.size)
        assertEquals("m/44'/60'/0'/0/8", addresses.first().path)
        store.prepare(request())
        val task = assertNotNull(state.pendingTasks.firstOrNull())
        assertEquals(wallet.id, task.walletId)
        assertTrue(SIGNER in task.addresses)
        assertFailsWith<VaultError.AuthenticationFailed> { sign(task.id, password = "Other-Password2") }
        assertFalse(state.hasSignedResponse)
        assertEquals(0, engine.invocationCount(EngineOperation.SignPlanTask), "A wrong password never reaches the engine.")
        sign(task.id)
        assertTrue(state.hasSignedResponse)
        assertEquals(setOf(task.id), state.completedTaskIds)
        assertTrue(state.signatureFrames.first().lowercase().startsWith("ur:eth-signature/"))
        // The result card shows what the QR carries, decoded back from its frames.
        val signed = assertNotNull(state.signaturePayload)
        assertEquals(QrPayloadView.Format.Json, signed.format)
        assertEquals("Payload (JSON)", signed.titleEnglish)
        assertTrue(signed.summary().startsWith("ur:eth-signature · CBOR · "))
        assertTrue(signed.text.contains("\"signature\"") && signed.text.contains("\"request-id\""))
        assertEquals(1, state.receipts.size)
        assertEquals(PublicFixtures.FINGERPRINT, state.receipts.first().masterFingerprint, "Activity shows the same fingerprint as the wallet card.")
        store.prepare(request(nonce = 1))
        assertNull(state.signaturePayload, "A new request clears the previous result's payload.")
        val next = assertNotNull(state.pendingTasks.firstOrNull())
        assertFailsWith<VaultError.AuthenticationFailed> { sign(next.id, password = "") }
        assertFalse(state.hasSignedResponse)
        sign(next.id)
        assertEquals(2, state.receipts.size)
        val journal = fixture.cipher.open(fixture.journalFile.readBytes()).decodeToString()
        assertFalse(journal.contains("abandon"))
        assertFalse(journal.contains(PASSWORD))
    }

    @Test
    fun missingAndConflictingSelectorsNeverProduceAnAuthorizableTask() = DeviceStore.run {
        makeStore()
        val missing = JsonObject(request() - "address" - "masterFingerprint")
        store.prepare(missing)
        assertNotNull(state.review)
        assertNull(state.plan)
        assertNotNull(state.matchingIssue)
        assertFailsWith<AppIssue> { sign("unmatched") }
        val conflicting = JsonObject(request() + ("address" to JsonPrimitive("0x1111111111111111111111111111111111111111")))
        store.prepare(conflicting)
        assertNull(state.plan)
        assertNotNull(state.matchingIssue)
        assertFalse(state.hasSignedResponse)
    }

    @Test
    fun stalePublicCacheCannotAuthorizeADifferentEncryptedSeed() = DeviceStore.run {
        val wallet = makeStore()
        val seed = PHRASE.asSecret { words -> ALTERNATE_PASSPHRASE.asSecret { fixture.seed.hex(words, it) } }
        val different = WalletPublicMetadata.parse(com.bittool.signer.core.engine.EngineCalls(DeviceEngine.real).derivePublicIndex(seed))
        fixture.publicRepository.save(different, wallet.id, null, fixture.publicDataKey())
        store.reload()
        val address = assertNotNull(different.evmAddress)
        val otherRequest = JsonObject(request() + ("address" to JsonPrimitive(address)) + ("masterFingerprint" to JsonPrimitive(different.masterFingerprint)))
        store.prepare(otherRequest)
        val task = assertNotNull(state.pendingTasks.firstOrNull())
        // The engine re-derives the identity from the decrypted seed and refuses: an engine-authored error, verbatim.
        val refused = assertFailsWith<EngineException> { sign(task.id) }
        assertEquals("SIGNER", refused.code)
        assertFalse(state.hasSignedResponse)
        assertTrue(state.receipts.isEmpty())
        PASSWORD.asSecret { store.completeSetup(wallet.id, it) }
        assertEquals(PublicFixtures.FINGERPRINT, state.publicMetadata(wallet.id)?.masterFingerprint)
    }

    @Test
    fun backgroundCancelsInFlightTaskWithoutLosingTheReviewedRequest() = DeviceStore.run {
        makeStore()
        store.prepare(request())
        val task = assertNotNull(state.pendingTasks.firstOrNull())
        val commitment = state.review?.requestHash
        val gate = engine.holdNext(EngineOperation.SignPlanTask)
        val signing = async { runCatching { sign(task.id) } }
        gate.waitUntilStarted()
        store.suspendSession()
        gate.release()
        assertIs<CancellationException>(signing.await().exceptionOrNull(), "A backgrounded authorization must be discarded.")
        assertFalse(state.hasSignedResponse)
        assertTrue(state.completedTaskIds.isEmpty())
        assertTrue(state.receipts.isEmpty())
        assertEquals(commitment, state.review?.requestHash)
        assertFalse(state.busy)
        sign(task.id)
        assertTrue(state.hasSignedResponse)
    }

    @Test
    fun replacingOrRejectingARequestDiscardsLateResults() = DeviceStore.run {
        makeStore()
        val gate = engine.holdNext(EngineOperation.ResolveSigningPlan)
        val resolving = async { runCatching { store.prepare(request()) } }
        gate.waitUntilStarted()
        store.clearRequest()
        gate.release()
        assertIs<CancellationException>(resolving.await().exceptionOrNull(), "Rejected matching work must stay cancelled.")
        assertNull(state.plan)
        assertNull(state.review)
        store.prepare(request())
        val task = assertNotNull(state.pendingTasks.firstOrNull())
        val signingGate = engine.holdNext(EngineOperation.SignPlanTask)
        val signing = async { runCatching { sign(task.id) } }
        signingGate.waitUntilStarted()
        store.prepare(request(nonce = 2))
        val current = state.review?.requestHash
        signingGate.release()
        assertIs<CancellationException>(signing.await().exceptionOrNull(), "Replaced requests must not publish signatures.")
        assertEquals(current, state.review?.requestHash)
        assertFalse(state.hasSignedResponse)
        assertTrue(state.receipts.isEmpty())
    }

    @Test
    fun interruptedMatchingCanRetryTheFrozenRequestWithoutAuthorization() = DeviceStore.run {
        makeStore()
        val gate = engine.holdNext(EngineOperation.ResolveSigningPlan)
        val resolving = async { runCatching { store.prepare(request()) } }
        gate.waitUntilStarted()
        val hash = assertNotNull(state.review?.requestHash)
        assertNull(state.plan)
        store.suspendSession()
        assertEquals(MatchingIssue.Interrupted, state.matchingIssue)
        gate.release()
        assertIs<CancellationException>(resolving.await().exceptionOrNull(), "Interrupted matching must discard its old result.")
        store.retryMatching()
        assertEquals(hash, state.review?.requestHash)
        assertEquals(hash, state.plan?.requestHash)
        assertEquals(1, state.pendingTasks.size)
        assertNull(state.matchingIssue)
        assertTrue(state.completedTaskIds.isEmpty())
        assertFalse(state.hasSignedResponse)
        assertEquals(0, engine.invocationCount(EngineOperation.SignPlanTask))
        assertFailsWith<AppIssue> { store.retryMatching() }
    }

    @Test
    fun publicCollectionChangesCancelInFlightSignaturesAndLateMatches() = DeviceStore.run {
        val wallet = makeStore()
        val metadata = assertNotNull(state.publicMetadata(wallet.id))
        store.prepare(request())
        val task = assertNotNull(state.pendingTasks.firstOrNull())
        val hash = state.review?.requestHash
        val gate = engine.holdNext(EngineOperation.SignPlanTask)
        val signing = async { runCatching { sign(task.id) } }
        gate.waitUntilStarted()
        fixture.publicRepository.remove(wallet.id)
        store.reload()
        assertNull(state.plan)
        assertEquals(hash, state.review?.requestHash)
        assertEquals(MatchingIssue.WalletsChanged, state.matchingIssue)
        gate.release()
        assertIs<CancellationException>(signing.await().exceptionOrNull(), "Changed public metadata must invalidate an in-flight signature.")
        assertFalse(state.hasSignedResponse)
        assertTrue(state.completedTaskIds.isEmpty())
        assertTrue(state.receipts.isEmpty())

        fixture.publicRepository.save(metadata, wallet.id, null, fixture.publicDataKey())
        store.reload()
        val matchingGate = engine.holdNext(EngineOperation.ResolveSigningPlan)
        val matching = async { store.retryMatching() }
        matchingGate.waitUntilStarted()
        fixture.publicRepository.remove(wallet.id)
        store.reload()
        matchingGate.release()
        matching.await()
        assertNull(state.plan)
        assertEquals(MatchingIssue.WalletsChanged, state.matchingIssue)
        assertEquals(hash, state.review?.requestHash)
    }

    @Test
    fun fixedMultiWalletTasksRequireSeparatePasswordsAndPublishOnlyWhenComplete() = DeviceStore.run {
        // The controlled plan tests the orchestration; chain-specific multi-signer byte preservation lives in the engine
        // tests (and SignerStoreEngineTest's two-cosigner PSBT through the store).
        val wallet = makeStore()
        val other = createWallet("Second fixed task", passphrase = ALTERNATE_PASSPHRASE, password = "Other-Password2")
        val tasks = listOf(wallet.id, other.id).mapIndexed { offset, id ->
            val metadata = assertNotNull(state.publicMetadata(id))
            val key = assertNotNull(metadata.raw.obj("evmDefaultAddress"))
            buildJsonObject {
                put("id", "task-$offset")
                put("localWalletId", id.toString())
                put("walletKeyId", metadata.walletKeyId)
                put("chain", "evm")
                putJsonArray("paths") { add(key["path"]!!) }
                putJsonArray("publicKeys") { add(key["publicKey"]!!) }
                putJsonArray("addresses") { add(key["address"]!!) }
            }
        }
        engine.useOrchestrationTasks(tasks)
        store.prepare(request())
        assertFailsWith<AppIssue> { sign("task-1", password = "Other-Password2") }
        sign("task-0")
        assertFalse(state.hasSignedResponse)
        assertEquals(listOf("task-1"), state.pendingTasks.map { it.id })
        val originalPlanHash = state.plan?.planHash
        store.renameWallet(other.id, "Rename does not change matching")
        assertEquals(originalPlanHash, state.plan?.planHash)
        assertEquals(setOf("task-0"), state.completedTaskIds)
        assertFailsWith<VaultError.AuthenticationFailed> { sign("task-1", password = PASSWORD) }
        assertEquals(setOf("task-0"), state.completedTaskIds)

        val publicIndex = assertNotNull(state.publicMetadata(other.id))
        fixture.publicRepository.remove(other.id)
        store.reload()
        assertNull(state.plan)
        assertTrue(state.completedTaskIds.isEmpty())
        assertFalse(state.hasSignedResponse)
        assertEquals(MatchingIssue.WalletsChanged, state.matchingIssue)
        fixture.publicRepository.save(publicIndex, other.id, null, fixture.publicDataKey())
        store.reload()
        store.retryMatching()
        assertEquals(listOf("task-0", "task-1"), state.pendingTasks.map { it.id })
        assertFailsWith<AppIssue> { sign("task-1", password = "Other-Password2") }
        sign("task-0")
        sign("task-1", password = "Other-Password2")
        assertTrue(state.hasSignedResponse)
        assertTrue(state.pendingTasks.isEmpty())
        assertEquals(3, state.receipts.size)
        assertEquals(listOf(other.id, wallet.id, wallet.id).map { it.toString() }, state.receipts.map { it.walletId })
    }

    @Test
    fun interruptedCreationDoesNotLeaveAnEncryptedOrPublicWallet() = DeviceStore.run {
        store.reload()
        val gate = engine.holdNext(EngineOperation.DerivePublicIndex)
        val creation = async { runCatching { createWallet("Cancelled fixture") } }
        gate.waitUntilStarted()
        store.suspendSession()
        gate.release()
        assertIs<CancellationException>(creation.await().exceptionOrNull(), "Suspension before persistence must cancel creation.")
        assertTrue(vault.list().isEmpty())
        assertTrue(state.wallets.isEmpty())
        assertTrue(fixture.publicRepository.directory.listFiles().orEmpty().none { it.name.endsWith(".json") })
    }

    @Test
    fun connectOpeningQrIsPreparedOnlyWhileAllowedAndClearedOnSuspend() = DeviceStore.run {
        val wallet = makeStore()
        delay(900)
        assertNull(store.preparedConnect(wallet.id), "Nothing is prepared until the shell allows it.")
        assertEquals(0, engine.invocationCount(EngineOperation.CreateWalletPairing))

        store.setConnectPrefetchAllowed(true)
        assertTrue(eventually { store.preparedConnect(wallet.id) != null })
        val prepared = assertNotNull(store.preparedConnect(wallet.id))
        // Connect opens on Wallet Level, so that is what is prepared.
        assertEquals(store.walletPairing(wallet.id), prepared.payload, "It is exactly Connect's opening QR.")
        assertTrue(prepared.payload.json.contains("\"protocol\" : \"bittool-signer-wallet\"") && !prepared.payload.json.contains("xprv"))
        val coin = store.pairing(wallet.id, Chain.Bitcoin, "native", "accountXpub", 0)
        val view = assertNotNull(coin.payloadView, "Coin-level QR codes carry a payload view.")
        assertTrue(view.format == QrPayloadView.Format.Json && view.summary().startsWith("ur:crypto-account · CBOR · "))
        assertTrue(view.text.contains("\"master-fingerprint\" : 1942346250") && view.text.contains("witness-public-key-hash"))
        assertTrue(!view.text.contains("xprv") && !view.text.contains("\"is-private\" : true"))
        val address = store.pairing(wallet.id, Chain.Tron, "standard", "address", 3)
        assertEquals(QrPayloadView.Format.Text, address.payloadView?.format)
        assertEquals(address.address, address.payloadView?.text)
        assertEquals(prepared.payload.frames.size, prepared.matrices.size, "The QR matrices are prepared too.")

        store.suspendSession()
        assertNull(store.preparedConnect(wallet.id))
        store.setConnectPrefetchAllowed(false)
    }

    @Test
    fun aPreparedQrThatOutlivesASuspendIsDiscarded() = DeviceStore.run {
        val wallet = makeStore()
        val gate = engine.holdNext(EngineOperation.CreateWalletPairing)
        store.setConnectPrefetchAllowed(true)
        gate.waitUntilStarted()
        store.suspendSession() // e.g. App Lock while the engine is still working
        gate.release()
        delay(400)
        assertNull(store.preparedConnect(wallet.id))
        store.setConnectPrefetchAllowed(false)
    }
}
