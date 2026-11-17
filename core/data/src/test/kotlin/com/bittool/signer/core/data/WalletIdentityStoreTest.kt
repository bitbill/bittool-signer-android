package com.bittool.signer.core.data

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.JvmStore.Companion.PASSWORD
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.data.wallet.FilePublicWalletRepository
import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletIdentitySignature
import com.bittool.signer.core.model.json.int
import com.bittool.signer.core.model.json.str
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * KH v2 (1.1.0) in the store, ported from iOS `WalletIdentityTests`: every wallet saved from now on carries its identity
 * signature in its authenticated public data; a wallet from 1.0.0 asks for its password once on Wallet Level and never
 * again; the wallet-level code is only ever v2; the engine is only ever asked to sign the fixed text.
 */
class WalletIdentityStoreTest {

    private fun signCalls(store: JvmStore) = store.engine.calls.filter { it.first == "signKhV2" }

    /** What a 1.0.0 wallet looks like: its sidecar rebuilt from its own index, without a signature (iOS UI-test hook). */
    private suspend fun JvmStore.dropIdentity(id: WalletId) {
        val index = assertNotNull(state.publicMetadata(id))
        fixture.publicRepository.save(index, id, state.displayName(id), fixture.publicDataKey())
        store.reload()
    }

    private suspend fun JvmStore.authorize(id: WalletId, password: String = PASSWORD) =
        password.asSecret { store.authorizeWalletIdentity(id, it) }

    @Test
    fun aNewWalletIsSignedOnceAtCreationAndStoredWithItsIndex() = JvmStore.run {
        store.reload()
        val wallet = create()
        val identity = assertNotNull(state.walletIdentities[wallet.id])
        assertTrue(state.hasWalletIdentity(wallet.id))
        assertEquals(1, identity.epoch)
        // One signature, from the same seed as the index, for epoch 1 and nothing else.
        val calls = signCalls(this)
        assertEquals(1, calls.size)
        assertEquals(listOf("epoch", "seed"), calls.single().second.keys.sorted())
        assertEquals(1, calls.single().second.int("epoch"))
        assertEquals(engine.calls.first { it.first == "derivePublicIndex" }.second.str("seed"), calls.single().second.str("seed"))
        // It survives a relaunch: it lives in the sidecar, under the sidecar's MAC.
        val relaunched = fixture.relaunch()
        relaunched.reload()
        assertEquals(identity, relaunched.state.value.walletIdentities[wallet.id])
        // And the code carries it.
        store.walletPairing(wallet.id)
        val pairing = engine.calls.last { it.first == "createWalletPairing" }.second
        assertEquals(identity.signature, pairing.str("khSig"))
        assertEquals(1, pairing.int("khEpoch"))
        assertEquals("full", pairing.str("mode"))
    }

    @Test
    fun aWalletFrom100AsksForItsPasswordTheFirstTimeOnly() = JvmStore.run {
        store.reload()
        val wallet = create()
        dropIdentity(wallet.id)
        assertFalse(state.hasWalletIdentity(wallet.id), "A 1.0.0 sidecar has no signature.")
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id))
        // No code without it: the store asks for the password instead of producing a v1 code.
        val refused = assertFailsWith<AppIssue> { store.walletPairing(wallet.id) }
        assertEquals(UiText.Ios("Verify this wallet’s password once to create its pairing code."), refused.text)
        assertEquals(0, engine.count("createWalletPairing"))
        // A wrong password changes nothing.
        assertFailsWith<VaultError.AuthenticationFailed> { authorize(wallet.id, "Wrong-Password1") }
        assertFalse(state.hasWalletIdentity(wallet.id))
        // The first time: the password, once.
        val signsBefore = signCalls(this).size
        authorize(wallet.id)
        assertTrue(state.hasWalletIdentity(wallet.id))
        assertEquals(signsBefore + 1, signCalls(this).size)
        store.walletPairing(wallet.id)
        // The second time (and after a relaunch): no password, no signing.
        val signsAfter = signCalls(this).size
        store.walletPairing(wallet.id)
        val relaunched = fixture.relaunch()
        relaunched.reload()
        assertTrue(relaunched.state.value.hasWalletIdentity(wallet.id))
        relaunched.walletPairing(wallet.id)
        assertEquals(signsAfter, signCalls(this).size, "The stored signature is reused; the seed is not needed again.")
    }

    @Test
    fun authorisingWritesOnlyTheSignatureAndLeavesTheIndexAsItIs() = JvmStore.run {
        store.reload()
        val wallet = create("Kept name")
        dropIdentity(wallet.id)
        val index = assertNotNull(state.publicMetadata(wallet.id)).canonicalJson
        authorize(wallet.id)
        assertEquals(index, assertNotNull(state.publicMetadata(wallet.id)).canonicalJson)
        assertEquals("Kept name", state.displayName(wallet.id))
    }

    @Test
    fun aSignatureForAnotherWalletIsNeverStored() = JvmStore.run {
        store.reload()
        val wallet = create()
        dropIdentity(wallet.id)
        // The engine answers with a signature that belongs to a different wallet key.
        engine.on(EngineOperation.SignKhV2) { p -> FakeEngine.khV2(p.str("seed")!! + "other", 1).let { JsonObject(it + ("walletKeyId" to JsonPrimitive("f".repeat(64)))) } }
        val refused = assertFailsWith<AppIssue> { authorize(wallet.id) }
        assertEquals(UiText.Ios("This wallet’s identity signature could not be produced."), refused.text)
        assertFalse(state.hasWalletIdentity(wallet.id))
        // A malformed signature (not 65 bytes) is refused the same way.
        engine.on(EngineOperation.SignKhV2) { p -> JsonObject(FakeEngine.khV2(p.str("seed")!!, 1) + ("signature" to JsonPrimitive("AAAA"))) }
        assertFailsWith<AppIssue> { authorize(wallet.id) }
        assertFalse(state.hasWalletIdentity(wallet.id))
    }

    @Test
    fun withoutAnIndexThereIsNothingToAuthorise() = JvmStore.run {
        store.reload()
        val wallet = fixture.createVaultOnly("Needs repair", FakeEngine.PHRASE, PASSWORD)
        store.reload()
        val refused = assertFailsWith<AppIssue> { authorize(wallet.id) }
        assertEquals(UiText.Ios("Repair this wallet before pairing it."), refused.text)
    }

    @Test
    fun repairSignsTheIdentityFromTheSameUnlock() = JvmStore.run {
        store.reload()
        val wallet = fixture.createVaultOnly("Needs repair", FakeEngine.PHRASE, PASSWORD)
        store.reload()
        assertFalse(state.hasWalletIdentity(wallet.id))
        PASSWORD.asSecret { store.completeSetup(wallet.id, it) }
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id))
        assertTrue(state.hasWalletIdentity(wallet.id))
        assertEquals(1, signCalls(this).size)
    }

    @Test
    fun aPasswordResetSignsAMissingIdentitySilently() = JvmStore.run {
        store.reload()
        val wallet = create()
        dropIdentity(wallet.id)
        FakeEngine.PHRASE.asSecret { words ->
            "".asSecret { pass -> "Reset-Password2!".asSecret { pw -> store.resetPassword(wallet.id, words, pass, pw) } }
        }
        assertTrue(state.hasWalletIdentity(wallet.id))
        // A wallet that already has one is not signed again.
        val signs = signCalls(this).size
        FakeEngine.PHRASE.asSecret { words ->
            "".asSecret { pass -> "Reset-Password3!".asSecret { pw -> store.resetPassword(wallet.id, words, pass, pw) } }
        }
        assertEquals(signs, signCalls(this).size)
    }

    @Test
    fun aFailedSilentSignatureDoesNotStopThePasswordReset() = JvmStore.run {
        store.reload()
        val wallet = create()
        dropIdentity(wallet.id)
        engine.on(EngineOperation.SignKhV2) { throw EngineException("SIGNATURE", "Wallet identity signature failed verification.") }
        FakeEngine.PHRASE.asSecret { words ->
            "".asSecret { pass -> "Reset-Password2!".asSecret { pw -> store.resetPassword(wallet.id, words, pass, pw) } }
        }
        assertFalse(state.hasWalletIdentity(wallet.id))
        "Reset-Password2!".asSecret { pw -> vault.verifyPassword(wallet.id, pw) }
    }

    @Test
    fun theConnectPrefetchSkipsWalletsWithoutAnIdentity() = JvmStore.run {
        store.reload()
        val old = create("From 1.0.0")
        val signed = create("From 1.1.0", passphrase = "alternate")
        dropIdentity(old.id)
        store.setConnectPrefetchAllowed(true)
        test.advanceUntilIdle()
        assertNull(store.preparedConnect(old.id), "Its code needs the password first.")
        assertNotNull(store.preparedConnect(signed.id))
        assertEquals(1, engine.count("createWalletPairing"))
        authorize(old.id)
        test.advanceUntilIdle()
        assertNotNull(store.preparedConnect(old.id), "Once signed, it is prepared like any other.")
    }

    @Test
    fun aRebuiltIndexWithoutASignatureDropsTheStoredOne() = JvmStore.run {
        // FilePublicWalletRepository: a signature never outlives the index it was checked against.
        store.reload()
        val wallet = create()
        val key = fixture.publicDataKey()
        val repository: FilePublicWalletRepository = fixture.publicRepository
        assertNotNull(repository.snapshot(wallet.id, key).identity)
        val index = assertNotNull(state.publicMetadata(wallet.id))
        repository.rename(wallet.id, "Renamed", key)
        assertNotNull(repository.snapshot(wallet.id, key).identity, "A rename keeps the body, signature included.")
        repository.save(index, wallet.id, null, key)
        assertNull(repository.snapshot(wallet.id, key).identity)
        assertEquals("Renamed", repository.snapshot(wallet.id, key).displayName)
    }

    @Test
    fun aDamagedStoredSignatureCountsAsNotSignedYet() {
        // The sidecar parser accepts only epoch 1…1,000,000 and 88 base64 characters that decode to 65 bytes.
        val good = "I" + "A".repeat(86) + "="
        assertNotNull(WalletIdentitySignature.of(1, good))
        assertNotNull(WalletIdentitySignature.of(1_000_000, good))
        assertNull(WalletIdentitySignature.of(0, good))
        assertNull(WalletIdentitySignature.of(1_000_001, good))
        assertNull(WalletIdentitySignature.of(null, good))
        assertNull(WalletIdentitySignature.of(1, null))
        assertNull(WalletIdentitySignature.of(1, good.dropLast(1)))
        assertNull(WalletIdentitySignature.of(1, good.replace('A', '-')))
        assertNull(WalletIdentitySignature.of(1, "A".repeat(86) + "=="))
        assertFalse(WalletIdentitySignature.of(1, good).toString().contains(good), "never printed")
    }
}
