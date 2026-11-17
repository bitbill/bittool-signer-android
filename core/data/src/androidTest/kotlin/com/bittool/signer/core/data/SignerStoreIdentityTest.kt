package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.DeviceStore.Companion.PASSWORD
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KH v2 (1.1.0) through the whole store on the device: the bundled engine, the production vault and the authenticated
 * sidecar. The wallet-level code the store produces for the public test wallet is the iOS handoff fixture
 * (`wallet-pairing-current.json`, byte for byte: its five UR frames hash to [IOS_FRAMES_SHA256]), both for a wallet
 * created by 1.1.0 and for a 1.0.0 wallet after its password was entered once. Public BIP39 fixture only.
 */
@RunWith(AndroidJUnit4::class)
class SignerStoreIdentityTest {

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** A 1.0.0 sidecar: rebuilt from its own index without a signature (the iOS UI-test hook does the same). */
    private suspend fun DeviceStore.dropIdentity(id: WalletId) {
        val index = assertNotNull(state.publicMetadata(id))
        fixture.publicRepository.save(index, id, state.displayName(id), fixture.publicDataKey())
        store.reload()
    }

    @Test
    fun aNewWalletCarriesTheIosIdentityAndItsCodeIsTheIosFixture() = DeviceStore.run {
        val wallet = createWallet("Public identity fixture")
        val identity = assertNotNull(state.walletIdentities[wallet.id])
        assertEquals(1, identity.epoch)
        assertEquals(IOS_SIGNATURE_EPOCH_1, identity.signature, "docs/kh2_bittool_vectors.json, epoch 1")
        val pairing = store.walletPairing(wallet.id)
        assertEquals(5, pairing.frames.size)
        assertEquals(IOS_FRAMES_SHA256, sha256(pairing.frames.joinToString("\n")), "the iOS fixture frames, character for character")
        assertEquals(1666, pairing.payloadBytes)
        assertTrue(pairing.json.contains(IOS_SIGNATURE_EPOCH_1) && pairing.json.contains("\"khEpoch\" : 1"))
        // Stored in the authenticated body, never beside it, and nothing secret came with it.
        val file = parseJsonObject(sidecar(wallet.id).readText())!!
        val body = Base64.getDecoder().decode(file.str("body")).decodeToString()
        assertTrue(body.contains("\"khV2\"") && body.contains(IOS_SIGNATURE_EPOCH_1))
        for (text in listOf(file.toString(), body)) {
            assertFalse(text.contains(PASSWORD))
            assertFalse(text.contains("abandon"))
            assertFalse(text.contains("xprv"))
        }
        // A tampered signature breaks the sidecar's MAC: the wallet then needs repair, it never exports a forged one.
        sidecar(wallet.id).writeText(sidecar(wallet.id).readText().replace(file.str("body")!!.take(12), "AAAAAAAAAAAA"))
        store.reload()
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(wallet.id))
    }

    @Test
    fun aWalletFrom100AsksForItsPasswordOnceAndThenExportsTheSameCode() = DeviceStore.run {
        val wallet = createWallet("Public identity fixture")
        dropIdentity(wallet.id)
        assertFalse(state.hasWalletIdentity(wallet.id))
        assertFailsWith<AppIssue> { store.walletPairing(wallet.id) }
        assertFailsWith<VaultError.AuthenticationFailed> { "Wrong-Password1".asSecret { store.authorizeWalletIdentity(wallet.id, it) } }
        assertFalse(state.hasWalletIdentity(wallet.id))
        val signs = engine.invocationCount(EngineOperation.SignKhV2)
        PASSWORD.asSecret { store.authorizeWalletIdentity(wallet.id, it) }
        assertEquals(signs + 1, engine.invocationCount(EngineOperation.SignKhV2))
        assertEquals(IOS_SIGNATURE_EPOCH_1, state.walletIdentities[wallet.id]?.signature)
        assertEquals(IOS_FRAMES_SHA256, sha256(store.walletPairing(wallet.id).frames.joinToString("\n")))
        // The second time, and after a relaunch: no password, no signing.
        val relaunched = fixture.relaunch()
        relaunched.reload()
        assertEquals(IOS_FRAMES_SHA256, sha256(relaunched.walletPairing(wallet.id).frames.joinToString("\n")))
        assertEquals(signs + 1, engine.invocationCount(EngineOperation.SignKhV2))
    }

    @Test
    fun repairSignsTheIdentityFromTheSameUnlock() = DeviceStore.run {
        val wallet = makeStore(complete = false)
        assertFalse(state.hasWalletIdentity(wallet.id))
        PASSWORD.asSecret { store.completeSetup(wallet.id, it) }
        assertEquals(IOS_SIGNATURE_EPOCH_1, state.walletIdentities[wallet.id]?.signature)
    }

    companion object {
        /** iOS `docs/kh2_bittool_vectors.json`, epoch 1 (abandon ×11 about, no passphrase). */
        const val IOS_SIGNATURE_EPOCH_1 = "IDbq1RPzOmq1q37JrcXcPBa9vVjrpG0B+9/ltJXQjfWlDGoP/gwHDMeGFH5XdL693wnhbeAsBdfRj7x70s94Lm4="

        /** SHA-256 of iOS `wallet-pairing-current.json` `frames` joined by "\n" (5 × 717 characters). */
        const val IOS_FRAMES_SHA256 = "fab9474e868af0194ae6fb1dc1682c77611327ae8646991d81849d9b20d533de"
    }
}
