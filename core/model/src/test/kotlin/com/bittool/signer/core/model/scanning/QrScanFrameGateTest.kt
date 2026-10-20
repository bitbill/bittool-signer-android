package com.bittool.signer.core.model.scanning

import com.bittool.signer.core.model.scanning.QrScanFrameGate.Issue
import com.bittool.signer.core.model.scanning.QrScanFrameGate.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of `BitToolSignerTests/QRScannerTests.swift` (all six cases; seconds → milliseconds). */
class QrScanFrameGateTest {
    @Test
    fun recognizedButUnreadableAndEmptyCodesHaveExplicitFailures() {
        val gate = QrScanFrameGate()
        assertEquals(Outcome.Rejected(Issue.NonText), gate.receive(null, 0))
        assertEquals(Outcome.Rejected(Issue.Empty), gate.receive("", 100))
        assertEquals(Outcome.Rejected(Issue.Empty), gate.receive(" \n\t", 2_000))
        for (issue in Issue.entries) {
            assertTrue(issue.english().isNotEmpty())
            val text = issue.english(QrScanPurpose.RecoveryPhrase)
            assertTrue(text.isNotEmpty() && !text.contains("request"))
        }
    }

    @Test
    fun sizeLimitCountsUtf8BytesAndAcceptsTheExactBoundary() {
        val gate = QrScanFrameGate()
        val limit = QrScanFrameGate.MAXIMUM_BYTES
        val ascii = "a".repeat(limit)
        val unicode = "é".repeat(limit / 2)
        assertEquals(Outcome.Accepted(ascii), gate.receive(ascii, 0))
        assertEquals(Outcome.Accepted(unicode), gate.receive(unicode, 100))
        assertEquals(Outcome.Rejected(Issue.TooLarge), gate.receive(unicode + "a", 200))
    }

    @Test
    fun repeatedFramesThrottleWithoutExtendingTheRetryWindow() {
        val gate = QrScanFrameGate()
        val value = "UR:BYTES/1-2/TESTFRAME"
        assertEquals(Outcome.Accepted(value), gate.receive(value, 10_000))
        assertEquals(Outcome.Throttled, gate.receive(value, 10_400))
        assertEquals(Outcome.Throttled, gate.receive(value, 10_900))
        assertEquals(Outcome.Accepted(value), gate.receive(value, 11_000), "A QR held in place must be offered again after the throttle interval.")
    }

    @Test
    fun distinctAnimatedFramesAreDeliveredImmediatelyAndUnchanged() {
        val gate = QrScanFrameGate()
        val first = "UR:BYTES/1-2/TESTFRAMEONE"
        val next = "UR:BYTES/2-2/TESTFRAMETWO"
        val wrapped = " \nUR:BYTES/TESTFRAME\n"
        assertEquals(Outcome.Accepted(first), gate.receive(first, 10_000))
        assertEquals(Outcome.Accepted(next), gate.receive(next, 10_010))
        assertEquals(Outcome.Accepted(wrapped), gate.receive(wrapped, 10_020), "The camera must not rewrite bytes before protocol validation.")
    }

    @Test
    fun repeatedFailuresDoNotBlockAReadableFrame() {
        val gate = QrScanFrameGate()
        assertEquals(Outcome.Rejected(Issue.NonText), gate.receive(null, 0))
        assertEquals(Outcome.Throttled, gate.receive(null, 100))
        assertEquals(Outcome.Accepted("readable-request"), gate.receive("readable-request", 200))
    }

    @Test
    fun earlierClockValueCannotPermanentlySuppressAFrame() {
        val gate = QrScanFrameGate()
        assertEquals(Outcome.Accepted("request"), gate.receive("request", 50_000))
        val earlier = gate.receive("request", 1_000)
        assertEquals(Outcome.Accepted("request"), earlier)
        assertFalse(earlier is Outcome.Throttled)
    }
}
