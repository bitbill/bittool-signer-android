package com.bittool.signer.core.model.signing

import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.TransactionReview
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Plan/task validation (models-state.md §3.3), matching vocabulary (§5.6) and the receipt journal (§6, ActivityRecordTests). */
class SigningTest {
    private val hash = "a05ea69096eb986f75275407e4749ca938511775d67fed164e44f237d67efa57"
    private val walletId = "10000000-0000-0000-0000-000000000001"

    private fun task(id: String = "t1", extra: String = "") =
        """{"id":"$id","localWalletId":"$walletId","walletKeyId":"${"9".repeat(64)}","chain":"evm","paths":["m/44'/60'/0'/0/0"],"publicKeys":["03ab"],"targets":[{"signerSlot":2}]$extra}"""

    private fun plan(tasks: String = "[${task()}]", status: String = "ready", planHash: String = "b".repeat(64), requestHash: String = hash) =
        parseJsonObject("""{"version":1,"status":"$status","requestHash":"$requestHash","planHash":"$planHash","review":{"x":1},"tasks":$tasks}""")!!

    @Test
    fun aReadyPlanKeepsItsWholeRawObject() {
        val raw = plan()
        val parsed = SigningPlan.parse(raw, hash)
        assertEquals(hash, parsed.requestHash)
        assertEquals(1, parsed.tasks.size)
        assertEquals(raw, parsed.raw, "the resolved object goes back to signPlanTask unchanged")
        val t = parsed.tasks.single()
        assertEquals(Chain.Evm, t.chain)
        assertEquals(walletId, t.walletId.toString())
        assertEquals(2, t.signerSlot)
        assertTrue(t.addresses.isEmpty() && t.inputs.isEmpty())
    }

    @Test
    fun everyPlanRuleFailsClosed() {
        val invalid = UiText.Ios("Invalid signing plan.")
        for (raw in listOf(
            plan(status = "noMatch"),
            plan(requestHash = hash.uppercase()),
            plan(planHash = "b".repeat(63)),
            plan(tasks = "[]"),
            plan(tasks = "[${(1..65).joinToString(",") { task("t$it") }}]"),
            plan(tasks = "[${task().replace("\"chain\":\"evm\"", "\"chain\":\"doge\"")}]"),
            plan(tasks = "[${task().replace(walletId, "1-1-1-1-1")}]"),
            plan(tasks = "[${task().replace("\"9".plus("9".repeat(63)), "\"99")}]"),
            plan(tasks = "[${task().replace("\"publicKeys\":[\"03ab\"]", "\"publicKeys\":[]")}]"),
            plan(tasks = "[${task().replace("\"id\":\"t1\"", "\"id\":\"\"")}]"),
        )) {
            assertEquals(invalid, assertFailsWith<AppIssue> { SigningPlan.parse(raw, hash) }.text)
        }
        val duplicate = assertFailsWith<AppIssue> { SigningPlan.parse(plan(tasks = "[${task()},${task()}]"), hash) }
        assertEquals(UiText.Ios("Duplicate signing task."), duplicate.text)
    }

    @Test
    fun taskDisplayValuesAreDistinctAndNonEmpty() {
        val t = SigningTask.parse(parseJsonObject(task(extra = ""","addresses":["a","","a","b"],"inputs":[0,2]"""))!!)
        assertEquals(listOf("a", "b"), t.displayAddresses)
        assertEquals(listOf(0, 2), t.inputs)
        assertEquals("#1, #3", ReviewPresentation.inputsSigned(t))
        assertEquals("3", ReviewPresentation.signerPosition(t))
        val mixedTargets = SigningTask.parse(parseJsonObject(task().replace("\"targets\":[{\"signerSlot\":2}]", "\"targets\":[{\"signerSlot\":2},\"x\"]"))!!)
        assertNull(mixedTargets.signerSlot)
    }

    @Test
    fun matchingIssuesRetryAndExplainLikeIos() {
        assertTrue(MatchingIssue.Interrupted.canRetry)
        assertTrue(MatchingIssue.WalletsChanged.canRetry)
        assertFalse(MatchingIssue.Unsupported.canRetry)
        assertEquals(MatchingExplanation.Unsupported, MatchingIssue.Unsupported.explanation)
        assertEquals(MatchingExplanation.NoMatch, MatchingIssue.Engine("No matching wallet").explanation)
        assertEquals(MatchingExplanation.MissingSigner, MatchingIssue.Engine("Missing signer information").explanation)
        assertEquals(MatchingExplanation.Ambiguous, MatchingIssue.Engine("Ambiguous wallet match").explanation)
        assertEquals(MatchingExplanation.SetupRequired, MatchingIssue.Engine("Wallet setup required").explanation)
        assertEquals(MatchingExplanation.Default, MatchingIssue.Engine("Invalid master fingerprint").explanation)
        assertFalse(MatchingIssue.Engine("No matching wallet").canRetry)
        assertFalse(MatchingIssue.Failure(UiText.Ios("Invalid signing plan.")).canRetry)
    }

    private fun receipt(i: Int, payload: Int = 10, title: String = "Send") = SigningReceipt(
        id = "00000000-0000-0000-0000-${i.toString().padStart(12, '0')}",
        date = 1_000L + i,
        chain = Chain.Evm,
        title = title,
        requestHash = "a".repeat(64),
        summary = "1 ETH",
        requestJson = "x".repeat(payload),
        responseFrames = listOf("UR:ETH-SIGNATURE/" + "y".repeat(payload)),
        responseText = "UR:ETH-SIGNATURE/" + "y".repeat(payload),
    )

    @Test
    fun anOversizedPayloadIsLeftOutInsteadOfTruncated() {
        val big = "a".repeat(SigningReceipt.PAYLOAD_LIMIT + 1)
        assertNull(SigningReceipt.stored(big))
        assertNotNull(SigningReceipt.stored("a".repeat(SigningReceipt.PAYLOAD_LIMIT)))
        assertNull(SigningReceipt.stored(""))
        assertNull(SigningReceipt.stored(null as String?))
        assertNull(SigningReceipt.stored(listOf(big)))
        assertNull(SigningReceipt.stored(emptyList()))
        assertNotNull(SigningReceipt.stored(listOf("ur:eth-signature/abc")))
        assertNull(SigningReceipt.stored(List(2_049) { "a" }))
        assertNull(SigningReceipt.stored("é".repeat(SigningReceipt.PAYLOAD_LIMIT / 2 + 1)), "the limit is UTF-8 bytes")
    }

    @Test
    fun aRecordWithoutPayloadsReportsThatItHasNone() {
        val bare = SigningReceipt(id = "00000000-0000-0000-0000-000000000001", date = 0, chain = Chain.Evm, title = "Send", requestHash = "a".repeat(64))
        assertFalse(bare.hasPayloads)
        val requestOnly = bare.copy(requestJson = "{}")
        assertTrue(requestOnly.hasPayloads)
        assertFalse(requestOnly.withoutPayloads().hasPayloads)
        assertEquals(bare.title, requestOnly.withoutPayloads().title)
    }

    @Test
    fun theLimitsCannotProduceAFileTheNextLaunchWouldReject() {
        val worstCase = SigningReceipt.PAYLOAD_RETENTION * 2 * SigningReceipt.PAYLOAD_LIMIT + 100 * 1_024
        assertTrue(worstCase < ReceiptJournal.FILE_LIMIT)
    }

    @Test
    fun theJournalRoundTripsAndOmitsNulls() {
        val records = listOf(receipt(1), SigningReceipt(id = "00000000-0000-0000-0000-000000000002", date = 5, chain = Chain.Tron, title = "TRX transfer", requestHash = "b".repeat(64), resultType = SigningReceipt.RESULT_PARTIAL))
        val bytes = ReceiptJournal.encode(records)
        val text = bytes.decodeToString()
        assertFalse(text.contains("null"))
        assertTrue(text.contains("\"chain\":\"tron\"") && text.contains("\"requestJSON\"") && text.contains("\"walletID\"").not())
        assertEquals(records, ReceiptJournal.decode(bytes))
        assertTrue(ReceiptJournal.decode(bytes)[1].isPartial)
    }

    @Test
    fun anyInvalidRecordRejectsTheWholeFile() {
        val bad = listOf(
            "not json".encodeToByteArray(),
            ReceiptJournal.encode(listOf(receipt(1).copy(requestHash = "ab"))),
            ReceiptJournal.encode(listOf(receipt(1).copy(title = "t".repeat(201)))),
            ReceiptJournal.encode(listOf(receipt(1).copy(requestJson = ""))),
            ReceiptJournal.encode(List(101) { receipt(it, 1) }),
            ReceiptJournal.encode(listOf(receipt(1))).decodeToString().replace("\"evm\"", "\"doge\"").encodeToByteArray(),
            ByteArray(ReceiptJournal.FILE_LIMIT + 1) { ' '.code.toByte() },
            // Swift `UUID(uuidString:)` is strict; java.util.UUID.fromString would accept these short forms.
            ReceiptJournal.encode(listOf(receipt(1).copy(id = "1-1-1-1-1"))),
            ReceiptJournal.encode(listOf(receipt(1).copy(walletId = "1-1-1-1-1"))),
        )
        for (bytes in bad) {
            assertEquals(UiText.Ios("Invalid activity record."), assertFailsWith<AppIssue> { ReceiptJournal.decode(bytes) }.text)
        }
    }

    @Test
    fun appendKeepsTheNewestHundredAndPayloadsOnTheNewestTwentyFive() {
        var journal = emptyList<SigningReceipt>()
        for (i in 1..(SigningReceipt.PAYLOAD_RETENTION + 2)) journal = ReceiptJournal.appended(receipt(i), journal)
        assertEquals(27, journal.size)
        assertEquals("00000000-0000-0000-0000-000000000027", journal.first().id)
        assertTrue(journal.take(SigningReceipt.PAYLOAD_RETENTION).all { it.hasPayloads })
        assertTrue(journal.drop(SigningReceipt.PAYLOAD_RETENTION).none { it.hasPayloads })
        for (i in 28..130) journal = ReceiptJournal.appended(receipt(i, 1), journal)
        assertEquals(ReceiptJournal.MAX_RECORDS, journal.size)
        assertTrue(ReceiptJournal.isValid(journal))
    }

    @Test
    fun appendShedsTheOldestPayloadsUntilTheFileFits() {
        // 25 records with two 30 KiB payloads each exceed nothing; with 33 KiB frames per record the encoding
        // would pass 2 MiB, but `stored` already refuses those, so build an over-limit journal directly.
        val fat = List(24) { receipt(it, 30_000) }
        val appended = ReceiptJournal.appended(receipt(99, 30_000), fat)
        assertTrue(ReceiptJournal.encode(appended).size <= ReceiptJournal.FILE_LIMIT)
        assertTrue(appended.first().hasPayloads, "the newest record keeps its payloads")
        assertTrue(appended.any { !it.hasPayloads }, "older records were shed")
        val firstShed = appended.indexOfFirst { !it.hasPayloads }
        assertTrue(appended.drop(firstShed).none { it.hasPayloads }, "payloads are shed from the oldest records first")
    }

    @Test
    fun aNewReceiptClampsItsTextAndKeepsResponsesOnlyForTheFinalTask() {
        val review = TransactionReview.parse(parseJsonObject("""{"chain":"evm","title":"${"T".repeat(250)}","summary":"${"s".repeat(200)}","requestHash":"$hash","network":{"chainId":"56","name":"BNB Smart Chain"},"riskLevel":"high"}""")!!)
        val t = SigningTask.parse(parseJsonObject(task())!!)
        val partial = SigningReceipt.create(1, t, review, hash, "73c5da0a", finalTask = false, requestJson = "{}", signatureFrames = listOf("F"), signatureText = "F")
        assertEquals(200, partial.title.length)
        assertEquals(160, partial.summary?.length)
        assertEquals(SigningReceipt.RESULT_PARTIAL, partial.resultType)
        assertNull(partial.responseFrames)
        assertEquals("56", partial.chainId)
        assertEquals("high", partial.riskLevel)
        assertEquals("BNB Smart Chain", partial.networkName)
        val final = SigningReceipt.create(1, t, review, hash, "73c5da0a", finalTask = true, requestJson = "{}", signatureFrames = listOf("F"), signatureText = "F")
        assertEquals(listOf("F"), final.responseFrames)
        assertEquals(SigningReceipt.RESULT_SIGNATURE, final.resultType)
        assertTrue(ReceiptJournal.isValid(listOf(final, partial)))
    }
}
