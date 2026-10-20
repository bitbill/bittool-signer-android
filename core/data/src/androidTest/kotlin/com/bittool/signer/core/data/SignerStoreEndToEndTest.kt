package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.doubles.PublicFixtures
import com.bittool.signer.core.data.doubles.PublicFixtures.PHRASE
import com.bittool.signer.core.data.doubles.PublicFixtures.SIGNER
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.engine.EngineOperation
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.json.strings
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.model.util.Hex
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One person's whole journey through `SignerStore` on the production stack — no test double anywhere below the store
 * except the transparent counting decorator around the real engine: the bundled QuickJS engine, `DefaultWalletVault`
 * whose outer layer is a non-exportable **Android Keystore** key per wallet, the Keystore HMAC key authenticating the
 * public sidecar, `KeystoreJournalCipher` sealing `receipts.bin`, the asset `DefaultBip39`, the native
 * `Pbkdf2Bip39Seed`, `SystemSecureRandomSource` and the ZXing encoder ([DeviceCrypto], [RealStoreFixture]).
 *
 * The requests are the published round-trip vectors (`docs/bittool-handoff/fixtures/signing-roundtrips.json` of the
 * iOS repository, engine-contract.md §11.1 V-ROUNDTRIP: a two-part `eth-sign-request` for an EIP-191 personal message
 * and a two-part `crypto-psbt` spending the reference wallet's first BIP84 address), entered the three ways the app
 * accepts them: pasted text (`decodePayload`), batch frames (`decodeQR`) and the incremental scan session. The
 * responses must equal the vectors' frames exactly (RFC 6979 signatures are deterministic). Public BIP39 fixtures and
 * synthetic transactions only; nothing is broadcast.
 */
@RunWith(AndroidJUnit4::class)
class SignerStoreEndToEndTest {

    @Test
    fun aReferenceWalletSignsAPersonalMessageAndAPsbtAndActivityKeepsEveryReceiptOnTheProductionStack() =
        DeviceStore.run(deviceKeys = DeviceCrypto.keystoreDeviceKeys()) {
            val personal = RoundTrip.load("evm-personal")
            val bitcoin = RoundTrip.load("bitcoin-bip84-psbt")
            store.reload()
            assertTrue(state.walletLoadSucceeded && state.wallets.isEmpty())

            // ---- 1. Import the BIP39 reference phrase as a person types it; setup canonicalises it first.
            val typed = "  Abandon abandon abandon abandon abandon abandon\nabandon abandon abandon abandon abandon ABOUT "
            val canonical = withContext(Dispatchers.Default) { fixture.bip39.canonicalize(typed) }
            assertEquals(PHRASE, canonical.phrase)
            assertEquals(Bip39Language.English, canonical.language)
            assertNull(PHRASE.asSecret { words -> "".asSecret { store.existingWallet(words, it) } }, "Nothing to reset yet.")
            val wallet = createWallet("End-to-end reference", password = WALLET_PASSWORD, phrase = canonical.phrase)
            val id = wallet.id
            assertTrue(DeviceCrypto.hasKeystoreKey(id), "The vault's outer layer is this wallet's own Keystore key.")
            val sealedVault = String(fixture.vaultBytes(id), Charsets.ISO_8859_1)
            assertFalse(sealedVault.contains("abandon") || sealedVault.contains(WALLET_PASSWORD), "The vault file holds no clear secret.")
            assertEquals(listOf(id), state.wallets.map { it.id })
            assertEquals("End-to-end reference", state.displayName(id))
            assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(id))
            val index = assertNotNull(state.publicMetadata(id))
            assertEquals(PublicFixtures.WALLET_KEY_ID, index.walletKeyId)
            assertEquals("73C5DA0A", state.fingerprint(id))
            assertEquals(id, PHRASE.asSecret { words -> "".asSecret { store.existingWallet(words, it) } })

            // ---- 2. Public derivation matches BIP84 (bips/bip-0084.mediawiki "Test vectors").
            val native = assertNotNull(index.raw.objects("bitcoinPublicNodes")?.single { it.str("scriptProfile") == "native" })
            assertEquals("m/84'/0'/0'", native.str("path"))
            assertEquals(BIP84_ACCOUNT_ZPUB, Base58Check.withVersion(assertNotNull(native.str("xpub")), ZPUB_VERSION))
            val receive = store.addresses(id, Chain.Bitcoin, "native", start = 0, count = 2)
            assertEquals(BIP84_RECEIVE.map { it.first }, receive.map { it.path })
            assertEquals(BIP84_RECEIVE.map { it.second }, receive.map { it.publicKey })
            assertEquals(BIP84_RECEIVE.map { it.third }, receive.map { it.address })
            assertEquals(SIGNER, store.addresses(id, Chain.Evm, "standard", start = 0, count = 1).single().address)

            // ---- 3. EIP-191 personal_sign, pasted as one UR part per line (decodePayload → decodeQR).
            store.importPayload(personal.requestFrames.joinToString("\n"))
            assertEquals(1, engine.invocationCount(EngineOperation.DecodePayload))
            val messageReview = assertNotNull(state.review, "${state.matchingIssue}")
            assertEquals(Chain.Evm, messageReview.chain)
            // The engine shows the decoded EIP-191 text quoted, exactly as signed.
            assertEquals("\"${personal.message}\"", messageReview.fields.single { it.label == "Message" }.value)
            val messageTask = assertNotNull(state.pendingTasks.singleOrNull(), "${state.matchingIssue}")
            assertEquals(id, messageTask.walletId)
            assertTrue(SIGNER in messageTask.addresses)
            assertEquals(messageReview.requestHash, state.plan?.requestHash)
            // A wrong password is refused by the vault before the engine sees anything.
            assertFailsWith<VaultError.AuthenticationFailed> { sign(messageTask.id, password = "Wrong-Password9", risk = true) }
            assertEquals(0, engine.invocationCount(EngineOperation.SignPlanTask))
            assertFalse(state.hasSignedResponse)
            assertTrue(state.receipts.isEmpty())
            sign(messageTask.id, password = WALLET_PASSWORD, risk = messageReview.requiresAcknowledgement)
            assertEquals(personal.responseFrames, state.signatureFrames, "The eth-signature equals the published vector.")
            assertEquals(personal.responseFrames.joinToString("\n"), state.signatureText)
            val messagePayload = assertNotNull(state.signaturePayload)
            assertEquals(QrPayloadView.Format.Json, messagePayload.format)
            assertTrue(messagePayload.summary().startsWith("ur:eth-signature · CBOR · "))
            assertTrue(messagePayload.text.contains(assertNotNull(personal.expected.str("signatureHex"))))
            store.clearRequest()

            // ---- 4. A BIP84 PSBT as an animated crypto-psbt, decoded from its frames (decodeQR).
            assertEquals(1.0, store.receive(bitcoin.requestFrames))
            assertEquals(1, engine.invocationCount(EngineOperation.DecodeQR))
            val psbtReview = assertNotNull(state.review, "${state.matchingIssue}")
            assertEquals(Chain.Bitcoin, psbtReview.chain)
            val psbtTask = assertNotNull(state.pendingTasks.singleOrNull(), "${state.matchingIssue}")
            assertEquals(id, psbtTask.walletId)
            assertEquals(listOf(BIP84_RECEIVE.first().first), psbtTask.paths)
            sign(psbtTask.id, password = WALLET_PASSWORD, risk = psbtReview.requiresAcknowledgement)
            assertEquals(bitcoin.responseFrames, state.signatureFrames, "The signed crypto-psbt equals the published vector.")
            val signedPsbt = assertNotNull(bitcoin.expected.str("signedPSBTBase64"))
            assertTrue(assertNotNull(state.signaturePayload).text.contains(signedPsbt))
            store.clearRequest()

            // ---- 5. The same PSBT as a pasted Base64 file (decodePayload's PSBT text path): one static frame back.
            store.importPayload(assertNotNull(bitcoin.vector.str("unsignedPSBTBase64")))
            val staticReview = assertNotNull(state.review)
            val staticTask = assertNotNull(state.pendingTasks.singleOrNull(), "${state.matchingIssue}")
            sign(staticTask.id, password = WALLET_PASSWORD, risk = staticReview.requiresAcknowledgement)
            assertEquals(listOf(signedPsbt), state.signatureFrames, "The same partial signature, in the request's own encoding.")
            assertEquals(signedPsbt, state.signatureText)
            store.clearRequest()

            // ---- 6. Activity recorded each signature, newest first, sealed, and every one reviews again.
            val reviews = listOf(staticReview, psbtReview, messageReview)
            val receipts = state.receipts
            assertEquals(reviews.map { it.requestHash }, receipts.map { it.requestHash })
            assertEquals(listOf(Chain.Bitcoin, Chain.Bitcoin, Chain.Evm), receipts.map { it.chain })
            assertTrue(receipts.all { it.resultType == SigningReceipt.RESULT_SIGNATURE && it.walletId == id.toString() })
            assertTrue(receipts.all { it.masterFingerprint == PublicFixtures.FINGERPRINT && it.hasPayloads })
            assertEquals(listOf(listOf(signedPsbt), bitcoin.responseFrames, personal.responseFrames), receipts.map { it.responseFrames })
            assertFalse(fixture.journalFile.readBytes().decodeToString().contains("requestJSON"), "receipts.bin is sealed.")
            val journal = fixture.journalText()
            assertTrue(journal.contains("requestJSON"))
            assertFalse(journal.contains("abandon") || journal.contains(WALLET_PASSWORD) || journal.contains("xprv"))
            assertReviewsAgain(store, receipts, reviews)

            // ---- 7. Relaunch: a new vault, journal cipher and store over the same files and Keystore keys.
            val relaunched = fixture.relaunch()
            relaunched.reload()
            val after = relaunched.state.value
            assertEquals(listOf(id), after.wallets.map { it.id })
            assertEquals(PublicMetadataStatus.Ready, after.metadataStatus(id), "The Keystore HMAC key still verifies the sidecar.")
            assertEquals(receipts, after.receipts)
            assertFalse(after.activityUnreadable)
            assertNull(after.error)
            assertReviewsAgain(relaunched, after.receipts, reviews)
            // The relaunched store still opens the vault: the personal message again, through the incremental scanner.
            relaunched.beginScan()
            var progress = 0.0
            for (frame in personal.requestFrames) progress = relaunched.receive(frame)
            relaunched.endScan()
            assertEquals(1.0, progress)
            assertEquals(messageReview.requestHash, relaunched.state.value.review?.requestHash)
            val again = assertNotNull(relaunched.state.value.pendingTasks.singleOrNull())
            WALLET_PASSWORD.asSecret { relaunched.sign(again.id, it, riskAcknowledged = true) }
            assertEquals(personal.responseFrames, relaunched.state.value.signatureFrames)
            assertEquals(4, relaunched.state.value.receipts.size)
            relaunched.clearRequest()

            // ---- 8. Deleting the wallet removes its vault file, Keystore key and sidecar; Activity is kept.
            assertFailsWith<VaultError.AuthenticationFailed> { "Wrong-Password9".asSecret { relaunched.deleteWallet(id, it) } }
            assertTrue(fixture.vaultFile(id).exists())
            WALLET_PASSWORD.asSecret { relaunched.deleteWallet(id, it) }
            assertFalse(fixture.vaultFile(id).exists())
            assertFalse(DeviceCrypto.hasKeystoreKey(id), "The wallet's Keystore key is deleted with it.")
            assertFalse(fixture.publicRepository.file(id).exists())
            assertTrue(relaunched.state.value.wallets.isEmpty())
            assertEquals(4, relaunched.state.value.receipts.size, "Activity records outlive the wallet.")
        }

    /** Each receipt's stored request reviews again (fresh runtime, no password) to the commitment it was signed under. */
    private suspend fun assertReviewsAgain(store: DefaultSignerStore, receipts: List<SigningReceipt>, reviews: List<TransactionReview>) {
        for ((receipt, signed) in receipts.zip(reviews)) {
            val again = store.reviewStoredRequest(assertNotNull(receipt.requestJson), receipt.requestHash)
            assertEquals(signed.requestHash, again.requestHash)
            assertEquals(signed.chain, again.chain)
            if (signed.chain == Chain.Evm) {
                assertEquals(signed.fields.map { it.label to it.value }, again.fields.map { it.label to it.value })
            }
        }
        assertNull(store.state.value.review, "Reviewing a record never adopts it as the pending request.")
    }

    /** One vector of `signing-roundtrips.json` (SHA-256 pinned, engine-contract.md §11.1). */
    private class RoundTrip(val vector: JsonObject) {
        val requestFrames: List<String> = checkNotNull(vector.obj("request")?.strings("frames"))
        val expected: JsonObject = checkNotNull(vector.obj("expected"))
        val responseFrames: List<String> = checkNotNull(expected.strings("responseFrames"))

        /** The EIP-191 message text (the signing bytes are UTF-8 text). */
        val message: String get() = checkNotNull(Hex.decode(checkNotNull(vector.str("signingBytesHex")))).decodeToString()

        companion object {
            private const val SHA256 = "05c8f96f972b42212cd420f09ffbea0e86783ac9b0d4fb2b69607c9e3be58021"

            private val vectors: List<JsonObject> by lazy {
                val bytes = checkNotNull(RoundTrip::class.java.getResourceAsStream("/vectors/signing-roundtrips.json")).use { it.readBytes() }
                check(Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes)) == SHA256) { "signing-roundtrips.json changed" }
                checkNotNull(parseJsonObject(bytes)?.objects("vectors"))
            }

            fun load(name: String) = RoundTrip(vectors.single { it.str("name") == name })
        }
    }

    /** Base58Check (BIP32 serialisation) to compare the engine's `xpub` with BIP84's `zpub` spelling of the same key. */
    private object Base58Check {
        private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private val BASE = BigInteger.valueOf(58)

        fun withVersion(encoded: String, version: ByteArray): String {
            val payload = decode(encoded)
            check(payload.size == 78) { "not a BIP32 key" }
            return encode(version + payload.copyOfRange(4, payload.size))
        }

        private fun checksum(bytes: ByteArray): ByteArray {
            val sha = MessageDigest.getInstance("SHA-256")
            return sha.digest(sha.digest(bytes)).copyOf(4)
        }

        private fun decode(text: String): ByteArray {
            var value = BigInteger.ZERO
            for (char in text) value = value * BASE + BigInteger.valueOf(ALPHABET.indexOf(char).also { check(it >= 0) }.toLong())
            val digits = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            val full = ByteArray(text.takeWhile { it == '1' }.length) + digits
            val payload = full.copyOf(full.size - 4)
            check(checksum(payload).contentEquals(full.copyOfRange(full.size - 4, full.size))) { "bad Base58Check checksum" }
            return payload
        }

        private fun encode(payload: ByteArray): String {
            val full = payload + checksum(payload)
            var value = BigInteger(1, full)
            val out = StringBuilder()
            while (value > BigInteger.ZERO) {
                val (quotient, remainder) = value.divideAndRemainder(BASE)
                out.append(ALPHABET[remainder.toInt()])
                value = quotient
            }
            repeat(full.takeWhile { it == 0.toByte() }.size) { out.append('1') }
            return out.reverse().toString()
        }
    }

    private companion object {
        /** This test's wallet password (passed explicitly: `DeviceStore.sign` defaults to the session fixture's). */
        const val WALLET_PASSWORD = "End-To-End-Password1"

        /** BIP84 "Account 0, root = m/84'/0'/0'" for the reference phrase. */
        const val BIP84_ACCOUNT_ZPUB =
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val ZPUB_VERSION = byteArrayOf(0x04, 0xB2.toByte(), 0x47, 0x46)

        /** BIP84 first and second receiving addresses: path, public key, address. */
        val BIP84_RECEIVE = listOf(
            Triple("m/84'/0'/0'/0/0", "0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c", "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"),
            Triple("m/84'/0'/0'/0/1", "03e775fd51f0dfb8cd865d9ff1cca2a158cf651fe997fdc9fee9c1d3b5e995ea77", "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"),
        )
    }
}
