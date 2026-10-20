package com.bittool.signer.core.scanner

import com.bittool.signer.core.model.scanning.QrScanFrameGate.Issue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The scanner's main-thread frame handling around `QrScanFrameGate` (iOS `metadataOutput`, `QRScannerView.swift:364-370`). */
class ScanFramesTest {
    private var now = 50_000L
    private val frames = ScanFrames { now }
    private val scanned = ArrayList<String>()

    private fun receive(vararg values: String?) = frames.receive(values.toList()) { scanned += it }

    @Test
    fun acceptedTextReachesTheScannerUnchangedAndClearsTheMessage() {
        receive(null)
        assertEquals(Issue.NonText, frames.issue)
        now += 100
        receive(" \nUR:BYTES/TESTFRAME\n")
        assertEquals(listOf(" \nUR:BYTES/TESTFRAME\n"), scanned)
        assertNull(frames.issue)
    }

    @Test
    fun rejectionsShowTheirIssue() {
        receive("")
        assertEquals(Issue.Empty, frames.issue)
        receive("a".repeat(16_385))
        assertEquals(Issue.TooLarge, frames.issue)
        assertEquals(emptyList(), scanned)
    }

    @Test
    fun aCodeHeldInViewIsOfferedAgainEverySecond() {
        receive("UR:BYTES/1-2/HELD")
        now += 400
        receive("UR:BYTES/1-2/HELD")
        now += 500
        receive("UR:BYTES/1-2/HELD")
        assertEquals(1, scanned.size)
        now += 100
        receive("UR:BYTES/1-2/HELD")
        assertEquals(2, scanned.size)
    }

    @Test
    fun animatedPartsInOneFramePassInOrder() {
        receive("UR:BYTES/1-3/A", "UR:BYTES/2-3/B")
        now += 33
        receive("UR:BYTES/3-3/C")
        assertEquals(listOf("UR:BYTES/1-3/A", "UR:BYTES/2-3/B", "UR:BYTES/3-3/C"), scanned)
    }

    @Test
    fun aRestartedCameraOffersAHeldCodeAtOnce() {
        receive("UR:BYTES/HELD")
        now += 200
        frames.restart()
        receive("UR:BYTES/HELD")
        assertEquals(2, scanned.size)
    }
}
