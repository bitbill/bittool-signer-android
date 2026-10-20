package com.bittool.signer.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.data.DeviceStore.Companion.PASSWORD
import com.bittool.signer.core.data.activity.EncryptedReceiptRepository
import com.bittool.signer.core.data.doubles.PublicFixtures
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.signing.ReceiptJournal
import com.bittool.signer.core.model.signing.SigningReceipt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of the six store cases of iOS `BitToolSignerTests/ActivityRecordTests.swift`: what Activity keeps for a
 * signature and what it can rebuild from it, over the bundled engine and the sealed journal. The four pure cases are
 * `:core:model`'s `SigningTest` / `ReviewModelTest`.
 */
@RunWith(AndroidJUnit4::class)
class SignerStoreActivityTest {
    private fun request(nonce: Int = 0, data: String = "0x") = PublicFixtures.evmRequest(nonce, data)

    private suspend fun DeviceStore.makeActivityStore() {
        store.reload()
        createWallet("Activity fixture", password = PASSWORD)
    }

    private suspend fun DeviceStore.signOnce(nonce: Int = 0, data: String = "0x", risk: Boolean = false) {
        store.prepare(request(nonce, data))
        val task = assertNotNull(state.pendingTasks.firstOrNull(), "${state.matchingIssue}")
        sign(task.id, risk = risk)
    }

    /** The journal's plaintext, decrypted with the test's Keystore stand-in. */
    private fun DeviceStore.journalText(): String = fixture.cipher.open(fixture.journalFile.readBytes()).decodeToString()

    @Test
    fun aSignatureKeepsTheRequestItSignedAndTheResponseItProduced() = DeviceStore.run {
        makeActivityStore()
        signOnce()
        val receipt = assertNotNull(state.receipts.firstOrNull())
        assertTrue(receipt.hasPayloads)
        val json = assertNotNull(receipt.requestJson)
        assertTrue(json.contains("\"chain\":\"evm\""))
        // Canonical JSON: the same request always produces the same record.
        assertEquals(json, parseJsonObject(json)!!.canonicalJson())
        assertEquals(request().canonicalJson(), json)
        // The output is kept exactly as the result screen showed and exported it.
        assertTrue(assertNotNull(receipt.responseFrames).first().lowercase().startsWith("ur:eth-signature/"))
        assertTrue(assertNotNull(receipt.responseText).lowercase().startsWith("ur:eth-signature/"))
        assertEquals(state.signatureFrames, receipt.responseFrames)
        assertEquals(state.signatureText, receipt.responseText)
        assertEquals(SigningReceipt.RESULT_SIGNATURE, receipt.resultType)
    }

    @Test
    fun theStoredRequestReviewsAgainToTheSameCommitment() = DeviceStore.run {
        makeActivityStore()
        store.prepare(request())
        val signedReview = assertNotNull(state.review)
        sign(state.pendingTasks.first().id)
        val receipt = assertNotNull(state.receipts.firstOrNull())
        // A fresh store, with no pending request, rebuilds the same review.
        val reloaded = fixture.relaunch()
        reloaded.reload()
        val again = reloaded.reviewStoredRequest(assertNotNull(receipt.requestJson), receipt.requestHash)
        assertEquals(signedReview.requestHash, again.requestHash)
        assertEquals(signedReview.chain, again.chain)
        assertEquals(signedReview.fields.map { it.label }, again.fields.map { it.label })
        assertEquals(signedReview.fields.map { it.value }, again.fields.map { it.value })
        assertEquals(signedReview.display?.action, again.display?.action)
        // Reviewing a record never adopts it as the pending request.
        assertNull(reloaded.state.value.review)
        assertNull(reloaded.state.value.plan)
    }

    @Test
    fun aStoredRequestThatDoesNotMatchItsFingerprintIsRefused() = DeviceStore.run {
        makeActivityStore()
        signOnce()
        val receipt = assertNotNull(state.receipts.firstOrNull())
        val json = assertNotNull(receipt.requestJson)
        // A different request reviews fine on its own, but not under this hash.
        assertFailsWith<Exception> { store.reviewStoredRequest(request(nonce = 9).canonicalJson(), receipt.requestHash) }
        assertFailsWith<Exception> { store.reviewStoredRequest("not json", receipt.requestHash) }
        // The untouched record still verifies.
        store.reviewStoredRequest(json, receipt.requestHash)
    }

    @Test
    fun thePayloadsAreKeptForTheMostRecentSignaturesOnly() = DeviceStore.run {
        makeActivityStore()
        val rounds = SigningReceipt.PAYLOAD_RETENTION + 2
        for (nonce in 0 until rounds) signOnce(nonce)
        assertEquals(rounds, state.receipts.size)
        assertTrue(state.receipts.take(SigningReceipt.PAYLOAD_RETENTION).all { it.hasPayloads })
        assertTrue(state.receipts.drop(SigningReceipt.PAYLOAD_RETENTION).none { it.hasPayloads })
        // Ageing a record out never removes the record itself.
        val oldest = state.receipts.last()
        assertTrue(oldest.title.isNotEmpty() && oldest.requestHash.length == 64 && oldest.summary?.isNotEmpty() == true)
        val reloaded = fixture.relaunch()
        reloaded.reload()
        assertEquals(state.receipts.map { it.id }, reloaded.state.value.receipts.map { it.id })
        assertEquals(SigningReceipt.PAYLOAD_RETENTION, reloaded.state.value.receipts.count { it.hasPayloads })
    }

    @Test
    fun noSecretReachesTheActivityFile() = DeviceStore.run {
        makeActivityStore()
        signOnce()
        val sealed = fixture.journalFile.readBytes().decodeToString()
        assertFalse(sealed.contains("requestJSON"), "The journal is sealed on Android (DECISIONS.md §9.3).")
        val journal = journalText()
        assertTrue(journal.contains("requestJSON") && journal.contains("responseText"))
        assertFalse(journal.contains("abandon"))
        assertFalse(journal.contains(PASSWORD))
        assertFalse(journal.lowercase().contains("xprv"))
        assertFalse(journal.contains("seed"))
    }

    @Test
    fun manySignaturesStayReadableAcrossALaunch() = DeviceStore.run {
        makeActivityStore()
        // Pad the calldata so every record carries a payload of real size.
        val padding = "f".repeat(8_000)
        for (nonce in 0 until SigningReceipt.PAYLOAD_RETENTION) signOnce(nonce, data = "0x$padding", risk = true)
        val sealedSize = fixture.journalFile.length()
        assertTrue(sealedSize <= EncryptedReceiptRepository.MAX_SEALED_BYTES, "$sealedSize bytes")
        assertTrue(journalText().encodeToByteArray().size <= ReceiptJournal.FILE_LIMIT)
        assertEquals(SigningReceipt.PAYLOAD_RETENTION, state.receipts.size)
        assertTrue(state.receipts.all { it.hasPayloads })
        val reloaded = fixture.relaunch()
        reloaded.reload()
        assertEquals(state.receipts.map { it.id }, reloaded.state.value.receipts.map { it.id })
        assertNull(reloaded.state.value.error, "A readable file never reports an error.")
        assertFalse(reloaded.state.value.activityUnreadable)
    }
}
