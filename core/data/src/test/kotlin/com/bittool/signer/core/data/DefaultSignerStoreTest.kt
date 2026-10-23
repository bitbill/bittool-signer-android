package com.bittool.signer.core.data

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.JvmStore.Companion.PASSWORD
import com.bittool.signer.core.data.doubles.AesGcmJournalCipher
import com.bittool.signer.core.data.doubles.EngineGate
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.signing.MatchingExplanation
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.model.signing.SigningReceipt
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The store rules of iOS `AppStore.swift` that need no real engine (pure pipeline, epochs, commits, Activity
 * failure paths, prefetch timing), over [FakeEngine] on the test scheduler. The SessionTests / ActivityRecordTests /
 * EngineIntegrationTests ports over the real engine are the instrumented `SignerStore*Test`s.
 */
class DefaultSignerStoreTest {

    private fun JvmStore.request(nonce: Int = 0) = FakeEngine.request(nonce)

    // ---- Loading (AppStore.swift:123-148) -------------------------------------------------------------------------

    @Test
    fun theStoreLoadsItselfOnceWhenNobodyAsked() = JvmStore.run {
        assertTrue(state.isLoading)
        test.advanceUntilIdle()
        assertFalse(state.isLoading)
        assertTrue(state.walletLoadSucceeded)
        assertTrue(state.wallets.isEmpty())
    }

    @Test
    fun aFailedLoadIsNotAnEmptyListAndShowsTheError() = JvmStore.run {
        store.reload()
        val wallet = create()
        vault.failList = VaultError.MalformedVault()
        store.reload()
        assertFalse(state.walletLoadSucceeded)
        assertFalse(state.isLoading)
        assertEquals(UiText.Ios("The wallet file is damaged or has an invalid format."), state.error)
        assertEquals(listOf(wallet.id), state.wallets.map { it.id }, "A failed load keeps the last list.")
        store.reload()
        assertTrue(state.walletLoadSucceeded)
    }

    @Test
    fun theDisplayedNameIsTheSidecarsAndStatusesFollowTheFiles() = JvmStore.run {
        store.reload()
        val legacy = fixture.createVaultOnly("Vault header name", FakeEngine.PHRASE, PASSWORD)
        store.reload()
        assertEquals(PublicMetadataStatus.NeedsCompletion, state.metadataStatus(legacy.id))
        assertTrue(state.hasIncompleteWallets)
        fixture.publicRepository.save(indexFor(), legacy.id, "Sidecar name", fixture.publicDataKey())
        store.reload()
        assertEquals("Sidecar name", state.wallets.single().name)
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(legacy.id))
        assertEquals(indexFor().displayFingerprint, state.fingerprint(legacy.id))
        sidecar(legacy.id).writeText("corrupt public fixture")
        store.reload()
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(legacy.id))
        assertEquals("Vault header name", state.wallets.single().name)
        assertEquals(legacy.id.uppercase, state.avatarIdentity(legacy.id))
    }

    @Test
    fun walletsSharingAnIdentityAreFlaggedAsDuplicates() = JvmStore.run {
        store.reload()
        val first = fixture.createVaultOnly("First", FakeEngine.PHRASE, PASSWORD)
        val second = fixture.createVaultOnly("Second", FakeEngine.PHRASE, PASSWORD)
        val other = fixture.createVaultOnly("Other", FakeEngine.PHRASE, PASSWORD, passphrase = "alternate")
        val key = fixture.publicDataKey()
        fixture.publicRepository.save(indexFor(), first.id, null, key)
        fixture.publicRepository.save(indexFor(), second.id, null, key)
        fixture.publicRepository.save(indexFor("alternate"), other.id, null, key)
        store.reload()
        assertEquals(setOf(first.id, second.id), state.duplicateWalletIds)
        // With duplicates the first wallet in vault order is the existing one (ARCHITECTURE.md §6 item 14).
        val existing = FakeEngine.PHRASE.asSecret { words -> "".asSecret { store.existingWallet(words, it) } }
        assertEquals(first.id, existing)
    }

    // ---- Creation (AppStore.swift:177-211) ---------------------------------------------------------------------------

    @Test
    fun creationStoresTheCanonicalWordsAndTheTrimmedName() = JvmStore.run {
        store.reload()
        val messy = "  ABANDON abandon\tabandon abandon abandon abandon abandon abandon abandon abandon abandon about "
        val metadata = messy.asSecret { words -> "".asSecret { pass -> PASSWORD.asSecret { pw -> store.createWallet("  Main  ", words, pass, pw) } } }
        assertEquals("Main", metadata.name)
        assertEquals(listOf(metadata.id), state.wallets.map { it.id })
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(metadata.id))
        assertTrue(sidecar(metadata.id).exists())
        PASSWORD.asSecret { pw -> vault.unlock(metadata.id, pw) }.use { secret ->
            assertEquals(FakeEngine.PHRASE, String(secret.mnemonic.chars()), "The vault stores the canonical words.")
        }
        assertEquals(1, engine.count("derivePublicIndex"))
        assertTrue(engine.calls.none { it.second.containsKey("mnemonic") }, "The engine receives a seed, never the phrase.")
    }

    @Test
    fun aSecondCreationWhileOneIsSavingIsRefused() = JvmStore.run {
        store.reload()
        val gate = vault.holdNextCreate()
        val first = test.async { create("First") }
        gate.waitUntilStarted()
        val refused = assertFailsWith<AppIssue> { create("Second") }
        assertEquals(UiText.Ios("A wallet is already being saved."), refused.text)
        gate.release()
        first.await()
        assertEquals(1, state.wallets.size)
    }

    @Test
    fun aPassphraseOverTheLimitAndADuplicateAreRefused() = JvmStore.run {
        store.reload()
        val long = "x".repeat(1_025)
        val tooLong = assertFailsWith<AppIssue> { create(passphrase = long) }
        assertEquals(UiText.Ios("The BIP39 passphrase exceeds 1,024 UTF-8 bytes."), tooLong.text)
        create()
        val duplicate = assertFailsWith<AppIssue> { create("Duplicate") }
        assertEquals(UiText.Ios("This recovery phrase and passphrase already belong to an existing wallet."), duplicate.text)
        create("Alternate", passphrase = "Public alternate test wallet")
        assertEquals(2, state.wallets.size)
    }

    @Test
    fun aFailedVaultWriteRemovesThePreparedSidecar() = JvmStore.run {
        store.reload()
        assertFailsWith<VaultError.InvalidPassword> { create(password = "weak") }
        assertTrue(state.wallets.isEmpty())
        assertTrue(fixture.publicRepository.directory.listFiles().orEmpty().none { it.name.endsWith(".json") })
    }

    @Test
    fun aNameTheVaultHeaderCannotHoldIsTheNameErrorBeforeAnythingIsWritten() = JvmStore.run {
        // QA audit A04: 40 grapheme clusters of a letter and 60 combining marks pass the grapheme limit, but a vault
        // header holding them would not read back. The person gets the iOS name error and every wallet stays available.
        store.reload()
        val kept = create("Kept", passphrase = "Public alternate test wallet")
        val heavy = ("e" + "́".repeat(60)).repeat(40)
        val refused = assertFailsWith<VaultError.InvalidName> { create(heavy) }
        assertEquals(UiText.Ios("Choose a wallet name of 1–40 characters."), refused.text)
        assertTrue(state.walletLoadSucceeded)
        assertEquals(listOf(kept.id), state.wallets.map { it.id })
        assertEquals(listOf("${kept.id}.json"), fixture.publicRepository.directory.list().orEmpty().filter { it.endsWith(".json") })
        // A rename follows the same rule.
        assertFailsWith<VaultError.InvalidName> { store.renameWallet(kept.id, heavy) }
        assertEquals("Kept", state.displayName(kept.id))
    }

    @Test
    fun theCollectionMustBeReadableBeforeAWalletIsAdded() = JvmStore.run {
        store.reload()
        vault.failList = VaultError.StorageFailure()
        val refused = assertFailsWith<AppIssue> { create() }
        assertEquals(UiText.Ios("The existing wallet collection could not be read. Retry before adding a wallet."), refused.text)
        assertTrue(fixture.publicRepository.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aCallerCancelledDuringTheVaultWriteStillGetsACommittedWallet() = JvmStore.run {
        // A1 of the contracts review: persistence is the success boundary, whoever cancels.
        store.reload()
        val gate = vault.holdNextCreate()
        val creation = test.launch { create() }
        gate.waitUntilStarted()
        creation.cancel()
        gate.release()
        test.advanceUntilIdle()
        assertTrue(creation.isCancelled)
        assertEquals(1, state.wallets.size, "The committed wallet is loaded although its caller went away.")
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(state.wallets.single().id))
    }

    @Test
    fun aSuspendBeforePersistenceCancelsCreation() = JvmStore.run {
        // SessionTests.interruptedCreationDoesNotLeaveAnEncryptedOrPublicWallet (JVM half).
        store.reload()
        val gate = EngineGate()
        engine.on(EngineOperation.DerivePublicIndex) { gate.pause(); FakeEngine.publicIndex(it.str("seed")!!) }
        val creation = test.async { runCatching { create() } }
        gate.waitUntilStarted()
        store.suspendSession()
        gate.release()
        assertIs<CancellationException>(creation.await().exceptionOrNull())
        assertTrue(vault.list().isEmpty())
        assertTrue(state.wallets.isEmpty())
        assertTrue(fixture.publicRepository.directory.listFiles().orEmpty().isEmpty())
    }

    // ---- Secrets: mnemonic, reveal (AppStore.swift:160-168, 474-480) ------------------------------------------------

    @Test
    fun aGeneratedPhraseComesFromFreshEntropy() = JvmStore.run {
        store.reload()
        val phrase = store.generateMnemonic(24).use { String(it.chars()) }
        assertEquals(FakeEngine.PHRASE, phrase)
        val entropy = engine.calls.single { it.first == "mnemonic" }.second.str("entropy")!!
        assertEquals(64, entropy.length, "24 words take 32 bytes of entropy.")
        assertFailsWith<VaultError.InvalidSecret> { store.generateMnemonic(13) }
        engine.on(EngineOperation.Mnemonic) { JsonObject(mapOf("value" to JsonPrimitive("fallback words"))) }
        assertEquals("fallback words", store.generateMnemonic(12).use { String(it.chars()) })
        engine.on(EngineOperation.Mnemonic) { JsonObject(emptyMap()) }
        assertEquals(UiText.Ios("Unable to generate recovery phrase."), assertFailsWith<AppIssue> { store.generateMnemonic(12) }.text)
    }

    @Test
    fun aPhraseArrivingAfterItsScreenClosedIsDiscarded() = JvmStore.run {
        store.reload()
        val gate = EngineGate()
        engine.on(EngineOperation.Mnemonic) { gate.pause(); JsonObject(mapOf("mnemonic" to JsonPrimitive(FakeEngine.PHRASE))) }
        val generating = test.async { runCatching { store.generateMnemonic(12) } }
        gate.waitUntilStarted()
        store.endSensitiveSession()
        gate.release()
        assertIs<CancellationException>(generating.await().exceptionOrNull())
    }

    @Test
    fun aRevealedSecretThatOutlivesASuspendIsWipedAndDiscarded() = JvmStore.run {
        store.reload()
        val wallet = create()
        store.beginSensitiveSession()
        PASSWORD.asSecret { store.reveal(wallet.id, it) }.use { assertEquals(FakeEngine.PHRASE, String(it.mnemonic.chars())) }
        val gate = vault.holdNextUnlock()
        val revealing = test.async { runCatching { PASSWORD.asSecret { store.reveal(wallet.id, it) } } }
        gate.waitUntilStarted()
        store.suspendSession()
        gate.release()
        assertIs<CancellationException>(revealing.await().exceptionOrNull())
        assertTrue(vault.issuedSecrets.last().mnemonic.isEmpty(), "The discarded secret was wiped, not dropped.")
    }

    // ---- Reset, rename, delete (AppStore.swift:235-242, 498-531) ----------------------------------------------------

    @Test
    fun resetPasswordRequiresThisWalletsPhrase() = JvmStore.run {
        store.reload()
        val legacy = fixture.createVaultOnly("Legacy", FakeEngine.PHRASE, PASSWORD)
        store.reload()
        suspend fun reset(passphrase: String) =
            FakeEngine.PHRASE.asSecret { w -> passphrase.asSecret { p -> "New-Password-22".asSecret { n -> store.resetPassword(legacy.id, w, p, n) } } }
        assertEquals(UiText.Ios("This wallet needs repair before its password can be reset."), assertFailsWith<AppIssue> { reset("") }.text)
        PASSWORD.asSecret { store.completeSetup(legacy.id, it) }
        assertEquals(UiText.Ios("This recovery phrase belongs to a different wallet."), assertFailsWith<AppIssue> { reset("other") }.text)
        assertEquals(0, vault.revision(legacy.id), "A refused reset changes nothing.")
        val epoch = state.securityEpoch
        reset("")
        assertEquals(1, vault.revision(legacy.id))
        assertEquals(epoch + 1, state.securityEpoch, "The password that authorised this session is gone.")
        "New-Password-22".asSecret { vault.verifyPassword(legacy.id, it) }
    }

    @Test
    fun renamingNeedsThePublicDataKeyAndKeepsThePlan() = JvmStore.run {
        store.reload()
        val wallet = create()
        store.prepare(request())
        val planHash = state.plan?.planHash
        assertNotNull(planHash)
        store.renameWallet(wallet.id, "  Renamed public fixture ")
        assertEquals("Renamed public fixture", state.displayName(wallet.id))
        assertEquals(planHash, state.plan?.planHash, "A rename never invalidates a plan.")
        assertFailsWith<VaultError.InvalidName> { store.renameWallet(wallet.id, "   ") }
        vault.replacePublicDataKey(null)
        assertEquals(UiText.Ios("Repair this wallet before renaming it."), assertFailsWith<AppIssue> { store.renameWallet(wallet.id, "Again") }.text)
        assertFailsWith<VaultError.WalletNotFound> { store.renameWallet(WalletId.random(), "Nobody") }
    }

    @Test
    fun aWrongPasswordDeletesNothingAndASuccessfulDeleteResetsTheSession() = JvmStore.run {
        store.reload()
        val wallet = create()
        store.prepare(request())
        val epoch = state.securityEpoch
        assertFailsWith<VaultError.AuthenticationFailed> { "Wrong-Password1".asSecret { store.deleteWallet(wallet.id, it) } }
        assertEquals(listOf(wallet.id), state.wallets.map { it.id })
        assertEquals(epoch, state.securityEpoch)
        assertNotNull(state.review)
        PASSWORD.asSecret { store.deleteWallet(wallet.id, it) }
        assertTrue(state.wallets.isEmpty())
        assertFalse(sidecar(wallet.id).exists())
        assertNull(state.review, "Deleting clears the request.")
        assertEquals(epoch + 1, state.securityEpoch)
        assertEquals(1, state.navigationResetEpoch)
        assertNull(state.error)
    }

    @Test
    fun aDeletedWalletsActivityRecordsAreKept() = JvmStore.run {
        // AppStore.swift:520-531 never touches the journal; Activity then labels the record "Deleted wallet"
        // (activity-settings.md §2.2).
        store.reload()
        val wallet = create()
        store.prepare(request())
        sign(state.pendingTasks.single().id)
        val receipt = state.receipts.single()
        PASSWORD.asSecret { store.deleteWallet(wallet.id, it) }
        assertEquals(listOf(receipt), state.receipts)
        assertEquals(wallet.id, state.receipts.single().walletIdOrNull)
        assertNull(state.displayName(wallet.id))
        assertEquals(listOf(receipt), fixture.receiptRepository.read())
    }

    @Test
    fun aPasswordChangeKeepsTheSessionTheReviewAndThePlan() = JvmStore.run {
        // AppStore.swift:514-519: only the password that opens the vault changes.
        store.reload()
        val wallet = create()
        store.prepare(request())
        val before = state
        PASSWORD.asSecret { current -> "Changed-Password2".asSecret { new -> store.changePassword(wallet.id, current, new) } }
        assertEquals(before.securityEpoch, state.securityEpoch)
        assertEquals(before.review, state.review)
        assertEquals(before.plan, state.plan)
        assertNull(state.matchingIssue)
        assertEquals(0, engine.count("releaseWarmContext"))
        assertFailsWith<VaultError.AuthenticationFailed> { sign(state.pendingTasks.single().id) }
        sign(state.pendingTasks.single().id, password = "Changed-Password2")
        assertTrue(state.hasSignedResponse)
    }

    @Test
    fun aPublicCacheThatCannotBeRemovedIsReported() = JvmStore.run {
        store.reload()
        val wallet = create()
        // A non-empty directory in place of the sidecar cannot be deleted.
        val file = sidecar(wallet.id)
        file.delete()
        file.mkdirs()
        java.io.File(file, "keep").writeText("x")
        PASSWORD.asSecret { store.deleteWallet(wallet.id, it) }
        assertTrue(state.wallets.isEmpty())
        assertEquals(UiText.Resource(R.string.data_public_cache_not_removed), state.error)
        assertEquals(1, state.navigationResetEpoch)
    }

    // ---- Request pipeline (AppStore.swift:330-410) -------------------------------------------------------------------

    @Test
    fun aReviewWithoutAValidCommitmentIsRefused() = JvmStore.run {
        store.reload()
        create()
        suspend fun refused(review: (JsonObject) -> JsonObject) {
            engine.on(EngineOperation.Review) { review(it.obj("request")!!) }
            val error = assertFailsWith<AppIssue> { store.prepare(request()) }
            assertEquals(UiText.Ios("Missing or invalid transaction commitment."), error.text)
            assertNull(state.review)
        }
        refused { FakeEngine.review(it, hash = "zz".repeat(32)) }
        refused { JsonObject(FakeEngine.review(it) + ("chain" to JsonPrimitive("bitcoin"))) }
        refused { JsonObject(FakeEngine.review(it) + ("fields" to JsonArray(emptyList()))) }
        refused { JsonObject(FakeEngine.review(it) - "chain") }
    }

    @Test
    fun requestsAreFrozenWithinTheirLimits() = JvmStore.run {
        store.reload()
        val huge = buildJsonObject { put("chain", "evm"); put("blob", "x".repeat(1_048_576)) }
        assertEquals(UiText.Ios("Request exceeds the supported size."), assertFailsWith<AppIssue> { store.prepare(huge) }.text)
        val nonFinite = buildJsonObject { put("chain", "evm"); put("value", Double.NaN) }
        assertEquals(UiText.Ios("Invalid request format."), assertFailsWith<AppIssue> { store.prepare(nonFinite) }.text)
        assertEquals(0, engine.count("review"))
        // The engine receives the frozen copy: keys sorted.
        create()
        store.prepare(request())
        val sent = engine.calls.last { it.first == "review" }.second.obj("request")!!
        assertEquals(request().canonicalJson(), sent.canonicalJson())
        assertEquals(sent.keys.sorted(), sent.keys.toList())
    }

    @Test
    fun aRequestTheEngineCannotSignShowsUnsupported() = JvmStore.run {
        store.reload()
        create()
        engine.on(EngineOperation.Review) { JsonObject(FakeEngine.review(it.obj("request")!!) + ("canSign" to JsonPrimitive(false))) }
        store.prepare(request())
        assertNotNull(state.review)
        assertEquals(MatchingIssue.Unsupported, state.matchingIssue)
        assertEquals(0, engine.count("resolveSigningPlan"))
    }

    @Test
    fun aResolverRefusalKeepsTheReviewWithTheEngineMessage() = JvmStore.run {
        store.reload()
        create()
        engine.on(EngineOperation.ResolveSigningPlan) {
            JsonObject(mapOf("status" to JsonPrimitive("ambiguous"), "message" to JsonPrimitive("Ambiguous wallet match")))
        }
        store.prepare(request())
        assertEquals(MatchingIssue.Engine("Ambiguous wallet match"), state.matchingIssue)
        assertEquals(MatchingExplanation.Ambiguous, state.matchingIssue?.explanation)
        engine.on(EngineOperation.ResolveSigningPlan) { JsonObject(mapOf("status" to JsonPrimitive("noMatch"))) }
        store.prepare(request())
        assertEquals(MatchingIssue.Engine("No matching wallet"), state.matchingIssue)
        assertNotNull(state.review)
        assertNull(state.plan)
    }

    @Test
    fun aPlanForAnotherIdentityIsAMatchingFailure() = JvmStore.run {
        store.reload()
        val wallet = create()
        engine.on(EngineOperation.ResolveSigningPlan) {
            FakeEngine.plan(it.obj("request")!!, listOf(FakeEngine.task("task-0", wallet.id.toString(), "c".repeat(64))))
        }
        store.prepare(request())
        assertEquals(MatchingIssue.Failure(UiText.Ios("Invalid signing wallet identity.")), state.matchingIssue)
        assertNull(state.plan)
        assertFalse(state.matchingIssue!!.canRetry)
    }

    @Test
    fun theRefinedReviewIsAdoptedOnlyForTheSameCommitment() = JvmStore.run {
        store.reload()
        create()
        val refined = { request: JsonObject, hash: String ->
            JsonObject(FakeEngine.review(request, hash) + ("summary" to JsonPrimitive("Change returns to your wallet")))
        }
        engine.on(EngineOperation.ResolveSigningPlan) {
            val request = it.obj("request")!!
            JsonObject(FakeEngine.resolve(it) + ("review" to refined(request, FakeEngine.requestHash(request))))
        }
        store.prepare(request())
        assertEquals("Change returns to your wallet", state.review?.summary)
        engine.on(EngineOperation.ResolveSigningPlan) {
            JsonObject(FakeEngine.resolve(it) + ("review" to refined(it.obj("request")!!, "d".repeat(64))))
        }
        store.prepare(request(1))
        assertEquals("1 wei to 0x1111…1111", state.review?.summary)
        assertNotNull(state.plan)
    }

    @Test
    fun hostEngineErrorsAreTranslatedAndEngineMessagesStayVerbatim() = JvmStore.run {
        store.reload()
        create()
        engine.on(EngineOperation.Review) { throw EngineException(EngineException.ENGINE_FAILURE, "The offline engine could not process this request.") }
        val host = assertFailsWith<EngineHostException> { store.prepare(request()) }
        assertEquals(UiText.Resource(R.string.data_engine_failure), host.text)
        assertEquals(EngineException.ENGINE_FAILURE, host.code)
        engine.on(EngineOperation.Review) { FakeEngine.review(it.obj("request")!!) }
        engine.on(EngineOperation.ResolveSigningPlan) { throw EngineException("REQUEST", "Unsupported signing request") }
        store.prepare(request())
        assertEquals(MatchingIssue.Failure(UiText.Verbatim("Unsupported signing request")), state.matchingIssue)
        engine.on(EngineOperation.ResolveSigningPlan) {
            throw EngineException(EngineException.REQUEST_TOO_LARGE, "This request exceeds the 1 MB offline signing limit.")
        }
        store.prepare(request())
        assertEquals(MatchingIssue.Failure(UiText.Resource(R.string.data_engine_request_too_large)), state.matchingIssue)
    }

    @Test
    fun suspendingWhileMatchingInterruptsAndRetryRematchesTheFrozenRequest() = JvmStore.run {
        store.reload()
        create()
        val gate = EngineGate()
        engine.on(EngineOperation.ResolveSigningPlan) { gate.pause(); FakeEngine.resolve(it) }
        val matching = test.async { runCatching { store.prepare(request()) } }
        gate.waitUntilStarted()
        store.suspendSession()
        assertEquals(MatchingIssue.Interrupted, state.matchingIssue)
        assertTrue(state.matchingIssue!!.canRetry)
        gate.release()
        assertIs<CancellationException>(matching.await().exceptionOrNull())
        engine.on(EngineOperation.ResolveSigningPlan) { FakeEngine.resolve(it) }
        store.retryMatching()
        assertNotNull(state.plan)
        assertNull(state.matchingIssue)
        assertEquals(UiText.Ios("No interrupted or unmatched request to retry."), assertFailsWith<AppIssue> { store.retryMatching() }.text)
    }

    /**
     * The caller goes away while the wallets are matched (the camera closed onto the review it had just read): the
     * review it leaves behind is Interrupted, with Retry, never "Finding the matching wallet…" forever.
     */
    @Test
    fun aCallerCancelledWhileMatchingLeavesTheReviewInterrupted() = JvmStore.run {
        store.reload()
        create()
        val gate = EngineGate()
        engine.on(EngineOperation.ResolveSigningPlan) { gate.pause(); FakeEngine.resolve(it) }
        val matching = test.async { store.prepare(request()) }
        gate.waitUntilStarted()
        matching.cancel()
        gate.release()
        assertIs<CancellationException>(runCatching { matching.await() }.exceptionOrNull())
        assertNotNull(state.review)
        assertNull(state.plan)
        assertEquals(MatchingIssue.Interrupted, state.matchingIssue)
        engine.on(EngineOperation.ResolveSigningPlan) { FakeEngine.resolve(it) }
        store.retryMatching()
        assertNotNull(state.plan)
    }

    @Test
    fun theBatchAndPasteLimitsAreEnforced() = JvmStore.run {
        store.reload()
        val tooMany = List(2_049) { "UR:ETH-SIGN-REQUEST/$it" }
        assertEquals(UiText.Ios("QR transfer is too large."), assertFailsWith<AppIssue> { store.receive(tooMany) }.text)
        val tooBig = List(2) { "x".repeat(600_000) }
        assertEquals(UiText.Ios("QR transfer is too large."), assertFailsWith<AppIssue> { store.receive(tooBig) }.text)
        assertEquals(UiText.Ios("Text payload exceeds 256 KB."), assertFailsWith<AppIssue> { store.importPayload("x".repeat(262_145)) }.text)
        engine.on(EngineOperation.DecodePayload) { JsonObject(mapOf("complete" to JsonPrimitive(false), "progress" to JsonPrimitive(0.5))) }
        assertEquals(
            UiText.Ios("The request is incomplete. Import all QR frames."),
            assertFailsWith<AppIssue> { store.importPayload("UR:ETH-SIGN-REQUEST/1-2/PART") }.text,
        )
        assertEquals(0, engine.count("decodeQR"))
    }

    @Test
    fun theScanSessionReportsProgressAndPreparesOnCompletion() = JvmStore.run {
        store.reload()
        create()
        store.beginScan()
        assertEquals(0.25, store.receive("UR:ETH-SIGN-REQUEST/1-2/PART"))
        assertNull(state.review)
        assertEquals(1.0, store.receive(FakeEngine.COMPLETE_FRAME))
        assertNotNull(state.review)
        assertNotNull(state.plan)
        store.endScan()
        assertNull(engine.scanSession)
        // Without a session the store falls back to batch decoding of the frames seen (iOS non-incremental engines).
        assertEquals(1.0, store.receive("UR:ETH-SIGN-REQUEST/1-1/ANY"))
        assertEquals(1, engine.count("decodeQR"))
    }

    @Test
    fun aScanThatCannotStartReportsTheTranslatedHostError() = JvmStore.run {
        store.reload()
        val failing = object : com.bittool.signer.core.engine.SignerEngine by engine {
            override suspend fun beginScanSession() =
                throw EngineException(EngineException.ENGINE_UNAVAILABLE, "The offline signing engine could not be started.")
        }
        val alternative = com.bittool.signer.core.data.doubles.StoreFixture(
            dataDir = dir, engine = failing, main = dispatcher, io = dispatcher, default = dispatcher, scope = scope,
        )
        val error = assertFailsWith<EngineHostException> { alternative.store.beginScan() }
        assertEquals(UiText.Resource(R.string.data_engine_unavailable), error.text)
    }

    // ---- Signing (AppStore.swift:412-472) ------------------------------------------------------------------------------

    @Test
    fun aWrongPasswordNeverReachesTheEngine() = JvmStore.run {
        store.reload()
        create()
        store.prepare(request())
        val task = state.pendingTasks.single()
        assertFailsWith<VaultError.AuthenticationFailed> { sign(task.id, password = "Other-Password2") }
        assertEquals(0, engine.count("signPlanTask"))
        assertFalse(state.busy)
        assertFalse(state.hasSignedResponse)
    }

    @Test
    fun onlyTheFirstPendingTaskCanBeSignedAndOnlyOnceAtATime() = JvmStore.run {
        store.reload()
        create()
        store.prepare(request())
        val task = state.pendingTasks.single()
        assertEquals(
            UiText.Ios("No matching signing task. Review the request again."),
            assertFailsWith<AppIssue> { sign("not-the-task") }.text,
        )
        val gate = EngineGate()
        engine.on(EngineOperation.SignPlanTask) { gate.pause(); FakeEngine.sign(it) }
        val signing = test.async { sign(task.id) }
        gate.waitUntilStarted()
        assertTrue(state.busy)
        assertEquals(UiText.Ios("This signing request is already being processed."), assertFailsWith<AppIssue> { sign(task.id) }.text)
        gate.release()
        signing.await()
        assertFalse(state.busy)
        assertTrue(state.hasSignedResponse)
        assertEquals(UiText.Ios("This signing request is already being processed."), assertFailsWith<AppIssue> { sign(task.id) }.text)
    }

    @Test
    fun aResultForAnotherPlanOrAnEmptyEncodingPublishesNothing() = JvmStore.run {
        store.reload()
        create()
        store.prepare(request())
        val task = state.pendingTasks.single()
        engine.on(EngineOperation.SignPlanTask) { JsonObject(FakeEngine.sign(it) + ("planHash" to JsonPrimitive("e".repeat(64)))) }
        assertEquals(UiText.Ios("Request changed. Review the transaction again."), assertFailsWith<AppIssue> { sign(task.id) }.text)
        engine.on(EngineOperation.SignPlanTask) { FakeEngine.sign(it) }
        engine.on(EngineOperation.EncodeResponse) { JsonObject(mapOf("frames" to JsonArray(emptyList()))) }
        assertEquals(UiText.Ios("Unable to encode signature response."), assertFailsWith<AppIssue> { sign(task.id) }.text)
        assertTrue(state.completedTaskIds.isEmpty())
        assertTrue(state.receipts.isEmpty())
        assertFalse(state.busy)
    }

    @Test
    fun theExportTextIsTheFramesJoinedWhenTheEngineSendsNone() = JvmStore.run {
        store.reload()
        create()
        store.prepare(request())
        engine.on(EngineOperation.EncodeResponse) {
            JsonObject(mapOf("frames" to JsonArray(listOf(JsonPrimitive("UR:A/1-2/X"), JsonPrimitive("UR:A/2-2/Y")))))
        }
        sign(state.pendingTasks.single().id)
        assertEquals(listOf("UR:A/1-2/X", "UR:A/2-2/Y"), state.signatureFrames)
        assertEquals("UR:A/1-2/X\nUR:A/2-2/Y", state.signatureText)
        assertNull(state.signaturePayload)
    }

    @Test
    fun eachTaskChainsThePreviousResultAndOnlyTheLastOnePublishes() = JvmStore.run {
        store.reload()
        val first = create("First")
        val second = create("Second", passphrase = "Public alternate test wallet", password = "Other-Password2")
        engine.on(EngineOperation.ResolveSigningPlan) {
            FakeEngine.plan(
                it.obj("request")!!,
                listOf(
                    FakeEngine.task("task-0", first.id.toString(), indexFor().walletKeyId),
                    FakeEngine.task("task-1", second.id.toString(), indexFor("Public alternate test wallet").walletKeyId),
                ),
            )
        }
        engine.on(EngineOperation.SignPlanTask) { JsonObject(FakeEngine.sign(it) + ("completedTaskIds" to JsonArray(listOf(it["taskId"]!!)))) }
        store.prepare(request())
        sign("task-0")
        assertFalse(state.hasSignedResponse)
        assertEquals(listOf("task-1"), state.pendingTasks.map { it.id })
        assertEquals(SigningReceipt.RESULT_PARTIAL, state.receipts.single().resultType)
        assertNull(state.receipts.single().responseText)
        sign("task-1", password = "Other-Password2")
        val calls = engine.calls.filter { it.first == "signPlanTask" }.map { it.second }
        assertNull(calls[0]["previousResult"])
        assertEquals("task-0", calls[1].obj("previousResult")?.str("taskId"), "The second task receives the first result.")
        assertTrue(state.hasSignedResponse)
        assertEquals(listOf(SigningReceipt.RESULT_SIGNATURE, SigningReceipt.RESULT_PARTIAL), state.receipts.map { it.resultType })
        assertEquals(listOf(second.id.toString(), first.id.toString()), state.receipts.map { it.walletId })
    }

    /** A two-wallet plan (task-0 for [first], task-1 for [second]); every result echoes its task. */
    private fun JvmStore.useTwoTaskPlan(first: WalletId, second: WalletId) {
        engine.on(EngineOperation.ResolveSigningPlan) {
            FakeEngine.plan(
                it.obj("request")!!,
                listOf(
                    FakeEngine.task("task-0", first.toString(), indexFor().walletKeyId),
                    FakeEngine.task("task-1", second.toString(), indexFor("Public alternate test wallet").walletKeyId),
                ),
            )
        }
    }

    @Test
    fun anEarlierTaskFinishedAfterASuspendIsDiscarded() = JvmStore.run(separateDefault = true) {
        // Android-only window: the result is frozen off the main thread before it is published (iOS freezes it on the
        // main actor, AppStore.swift:432-444). A suspend during the freeze must discard the partial signature like any
        // late result (scan-sign.md §18): nothing is marked signed, chained, or recorded.
        store.reload()
        val first = create("First")
        val second = create("Second", passphrase = "Public alternate test wallet", password = "Other-Password2")
        useTwoTaskPlan(first.id, second.id)
        store.prepare(request())
        val gate = EngineGate()
        engine.on(EngineOperation.SignPlanTask) { gate.pause(); FakeEngine.sign(it) }
        val signing = test.async { runCatching { sign("task-0") } }
        gate.waitUntilStarted()
        default.holdAfter(0)
        gate.release()
        test.runCurrent()
        assertEquals(1, default.waiting, "The result is being frozen.")
        store.suspendSession()
        default.release()
        assertIs<CancellationException>(signing.await().exceptionOrNull())
        assertTrue(state.completedTaskIds.isEmpty(), "A late partial signature is not marked signed.")
        assertTrue(state.receipts.isEmpty(), "…nor recorded in Activity.")
        assertFalse(state.busy)
        // The review and the plan stay, so the person signs the same task again with the password.
        assertEquals(listOf("task-0", "task-1"), state.pendingTasks.map { it.id })
        engine.on(EngineOperation.SignPlanTask) { FakeEngine.sign(it) }
        sign("task-0")
        assertNull(engine.calls.last { it.first == "signPlanTask" }.second["previousResult"], "The discarded result is never chained.")
        assertEquals(listOf("task-1"), state.pendingTasks.map { it.id })
        assertEquals(1, state.receipts.size)
    }

    @Test
    fun anEarlierTaskFinishedAfterARejectIsNotPublishedIntoTheClearedRequest() = JvmStore.run(separateDefault = true) {
        store.reload()
        val first = create("First")
        val second = create("Second", passphrase = "Public alternate test wallet", password = "Other-Password2")
        useTwoTaskPlan(first.id, second.id)
        store.prepare(request())
        val gate = EngineGate()
        engine.on(EngineOperation.SignPlanTask) { gate.pause(); FakeEngine.sign(it) }
        val signing = test.async { runCatching { sign("task-0") } }
        gate.waitUntilStarted()
        default.holdAfter(0)
        gate.release()
        test.runCurrent()
        assertEquals(1, default.waiting)
        store.clearRequest()
        default.release()
        assertIs<CancellationException>(signing.await().exceptionOrNull())
        assertNull(state.review)
        assertTrue(state.completedTaskIds.isEmpty())
        assertTrue(state.receipts.isEmpty(), "A rejected request leaves no Activity record.")
    }

    @Test
    fun aSeedDerivedAfterASuspendNeverReachesTheEngine() = JvmStore.run(separateDefault = true) {
        // Android-only window: the BIP39 seed is stretched off the main thread between the unlock and the engine call
        // (iOS derives it synchronously right after its session check, AppStore.swift:465-468).
        store.reload()
        val wallet = create()
        store.prepare(request())
        val task = state.pendingTasks.single()
        default.holdAfter(1) // the vault unlock passes, the seed derivation after it waits
        val signing = test.async { runCatching { sign(task.id) } }
        test.runCurrent()
        assertEquals(1, default.waiting, "The seed is being derived.")
        store.suspendSession()
        default.release()
        assertIs<CancellationException>(signing.await().exceptionOrNull())
        assertEquals(0, engine.count("signPlanTask"), "The seed of an ended session is never sent.")
        assertTrue(vault.issuedSecrets.last().mnemonic.isEmpty(), "The unlocked secret was wiped.")
        assertFalse(state.busy)
        // Repair re-derives the index from the vault's secret the same way.
        val derivations = engine.count("derivePublicIndex")
        default.holdAfter(1)
        val repairing = test.async { runCatching { PASSWORD.asSecret { store.completeSetup(wallet.id, it) } } }
        test.runCurrent()
        assertEquals(1, default.waiting)
        store.suspendSession()
        default.release()
        assertIs<CancellationException>(repairing.await().exceptionOrNull())
        assertEquals(derivations, engine.count("derivePublicIndex"))
        assertTrue(vault.issuedSecrets.last().mnemonic.isEmpty())
    }

    @Test
    fun aReloadThatChangesTheCollectionInvalidatesOnlyAnUnsignedPlan() = JvmStore.run {
        store.reload()
        val wallet = create()
        store.prepare(request())
        val index = state.publicMetadata(wallet.id)!!
        fixture.publicRepository.remove(wallet.id)
        store.reload()
        assertNull(state.plan)
        assertEquals(MatchingIssue.WalletsChanged, state.matchingIssue)
        assertNotNull(state.review, "The review stays so that the request can be matched again.")
        fixture.publicRepository.save(index, wallet.id, null, fixture.publicDataKey())
        store.reload()
        store.retryMatching()
        sign(state.pendingTasks.single().id)
        fixture.publicRepository.remove(wallet.id)
        store.reload()
        assertTrue(state.hasSignedResponse, "A finished signature survives wallet changes.")
        assertNotNull(state.plan)
    }

    @Test
    fun suspendKeepsTheReviewAndTheSignatureAndReleasesTheWarmRuntime() = JvmStore.run {
        store.reload()
        create()
        store.prepare(request())
        sign(state.pendingTasks.single().id)
        store.suspendSession()
        test.advanceUntilIdle()
        assertTrue(state.hasSignedResponse)
        assertNotNull(state.review)
        assertNull(state.matchingIssue)
        assertEquals(1, engine.count("releaseWarmContext"))
        store.clearRequest()
        assertNull(state.review)
        assertFalse(state.hasSignedResponse)
    }

    // ---- Activity (AppStore.swift:532-601) ---------------------------------------------------------------------------

    @Test
    fun anUnreadableJournalIsNeverOverwrittenUntilCleared() = JvmStore.run {
        store.reload()
        create()
        fixture.journalFile.writeBytes(byteArrayOf(1, 2, 3))
        store.reload()
        assertTrue(state.activityUnreadable)
        assertTrue(state.receipts.isEmpty())
        assertEquals(UiText.Resource(R.string.data_activity_unreadable), state.error)
        store.setError(null)
        store.prepare(request())
        sign(state.pendingTasks.single().id)
        assertTrue(state.hasSignedResponse, "The signature itself is unaffected.")
        assertEquals(UiText.Resource(R.string.data_activity_not_saved), state.error)
        assertTrue(fixture.journalFile.readBytes().contentEquals(byteArrayOf(1, 2, 3)), "The unreadable journal was not overwritten.")
        store.clearHistory()
        assertFalse(state.activityUnreadable)
        assertTrue(fixture.receiptRepository.read().isEmpty())
        store.prepare(request(1))
        sign(state.pendingTasks.single().id)
        assertEquals(1, state.receipts.size)
    }

    @Test
    fun aLostJournalKeyReadsAsUnreadable() = JvmStore.run {
        store.reload()
        create()
        store.prepare(request())
        sign(state.pendingTasks.single().id)
        (fixture.cipher as AesGcmJournalCipher).loseKey()
        val relaunched = fixture.relaunch()
        relaunched.reload()
        assertTrue(relaunched.state.value.activityUnreadable)
        assertEquals(UiText.Resource(R.string.data_activity_unreadable), relaunched.state.value.error)
    }

    @Test
    fun aJournalThatCannotBeWrittenReportsTheFailureAndKeepsTheSignature() = JvmStore.run {
        store.reload()
        create()
        fixture.journalFile.mkdirs()
        store.prepare(request())
        sign(state.pendingTasks.single().id)
        assertTrue(state.hasSignedResponse)
        assertEquals(UiText.Resource(R.string.data_activity_not_saved), state.error)
    }

    @Test
    fun aStoredRequestIsReviewedAgainOnlyUnderItsOwnFingerprint() = JvmStore.run {
        store.reload()
        val json = request().canonicalJson()
        val hash = FakeEngine.requestHash(request())
        assertEquals(hash, store.reviewStoredRequest(json, hash.uppercase()).requestHash, "The hash comparison ignores case.")
        assertNull(state.review, "Re-reviewing never adopts the request.")
        val read = UiText.Ios("The stored request could not be read.")
        assertEquals(read, assertFailsWith<AppIssue> { store.reviewStoredRequest("x".repeat(SigningReceipt.PAYLOAD_LIMIT + 1), hash) }.text)
        assertEquals(read, assertFailsWith<AppIssue> { store.reviewStoredRequest("not json", hash) }.text)
        assertEquals(UiText.Ios("Invalid local document."), assertFailsWith<AppIssue> { store.reviewStoredRequest("[1]", hash) }.text)
        assertEquals(
            UiText.Ios("The stored request does not match this signature's fingerprint."),
            assertFailsWith<AppIssue> { store.reviewStoredRequest(request(9).canonicalJson(), hash) }.text,
        )
    }

    // ---- Connect prefetch (AppStore.swift:255-301) -------------------------------------------------------------------

    @Test
    fun theOpeningConnectQrIsPreparedAfter600MsOnlyWhileAllowed() = JvmStore.run {
        store.reload()
        val wallet = create()
        test.advanceTimeBy(1_000)
        assertNull(store.preparedConnect(wallet.id), "Nothing is prepared until the shell allows it.")
        assertEquals(0, engine.count("createWalletPairing"))
        store.setConnectPrefetchAllowed(true)
        test.advanceTimeBy(599)
        test.runCurrent()
        assertNull(store.preparedConnect(wallet.id))
        test.advanceTimeBy(2)
        test.runCurrent()
        val prepared = assertNotNull(store.preparedConnect(wallet.id))
        assertEquals(prepared.payload.frames.size, prepared.matrices.size, "The QR matrices are prepared too.")
        assertEquals(store.walletPairing(wallet.id), prepared.payload)
        store.suspendSession()
        assertNull(store.preparedConnect(wallet.id))
        store.setConnectPrefetchAllowed(false)
        store.reload()
        test.advanceUntilIdle()
        assertNull(store.preparedConnect(wallet.id), "A disallowed prefetch is not rescheduled by a reload.")
    }

    @Test
    fun aPreparedQrIsDiscardedWhenItOutlivesASuspendOrCannotBeRendered() = JvmStore.run {
        store.reload()
        val wallet = create()
        val gate = EngineGate()
        engine.on(EngineOperation.CreateWalletPairing) { gate.pause(); FakeEngine.walletPairing() }
        store.setConnectPrefetchAllowed(true)
        test.advanceTimeBy(700)
        gate.waitUntilStarted()
        store.suspendSession()
        gate.release()
        test.advanceUntilIdle()
        assertNull(store.preparedConnect(wallet.id))
        engine.on(EngineOperation.CreateWalletPairing) { FakeEngine.walletPairing() }
        fixture.encoder.failing = setOf("UR:BYTES/2-2/B")
        store.setConnectPrefetchAllowed(true)
        test.advanceUntilIdle()
        assertNull(store.preparedConnect(wallet.id), "A frame that could not be rendered discards the entry.")
        fixture.encoder.failing = emptySet()
        store.setConnectPrefetchAllowed(true)
        test.advanceUntilIdle()
        assertNotNull(store.preparedConnect(wallet.id))
    }

    @Test
    fun walletsThatNeedRepairAreNotPrefetchedAndAnEngineErrorSkipsOnlyThatWallet() = JvmStore.run {
        store.reload()
        fixture.createVaultOnly("Needs repair", FakeEngine.PHRASE, PASSWORD)
        val ready = create("Ready", passphrase = "alternate")
        var calls = 0
        engine.on(EngineOperation.CreateWalletPairing) {
            calls++
            if (calls == 1) throw EngineException("QR_PROTOCOL", "Wallet pairing payload is too large") else FakeEngine.walletPairing()
        }
        store.setConnectPrefetchAllowed(true)
        test.advanceUntilIdle()
        assertEquals(1, calls, "Only the ready wallet is prefetched.")
        assertNull(store.preparedConnect(ready.id))
        store.setConnectPrefetchAllowed(true)
        test.advanceUntilIdle()
        assertNotNull(store.preparedConnect(ready.id))
        assertNotEquals(0, fixture.encoder.encoded.get())
    }

    @Test
    fun theStoreErrorIsPubliclySettable() = JvmStore.run {
        store.setError(UiText.Verbatim("x"))
        assertEquals(UiText.Verbatim("x"), state.error)
        store.setError(null)
        assertNull(state.error)
    }
}
