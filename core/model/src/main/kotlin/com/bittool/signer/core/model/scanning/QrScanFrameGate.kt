package com.bittool.signer.core.model.scanning

import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import com.bittool.signer.core.model.util.utf8Size

/** What a camera scan is for (iOS `QRScanPurpose`): it only changes the wording of rejections. */
enum class QrScanPurpose { SigningRequest, RecoveryPhrase }

/**
 * Port of iOS `QRScanFrameGate` (`QRScannerView.swift:207-239`, scan-sign.md §6.8): classifies each decoded QR value and
 * throttles identical outcomes without retaining rejected data. Times are monotonic milliseconds
 * (`SystemClock.elapsedRealtime()`), never the wall clock. Not thread-safe: feed it from the main thread, in frame order,
 * after applying the "prefer readable" rule (`values = readable.ifEmpty { listOf(null) }`).
 */
class QrScanFrameGate {
    enum class Issue {
        NonText,
        Empty,
        TooLarge;

        /**
         * The iOS English text for [purpose] (not in the iOS catalogue, gaps.md G6): `:core:scanner` shows its own
         * `strings_scanner.xml` resources with exactly these English texts; this is the reference for tests.
         */
        fun english(purpose: QrScanPurpose = QrScanPurpose.SigningRequest): String = when (purpose) {
            QrScanPurpose.SigningRequest -> when (this) {
                NonText -> "A QR code was found, but it doesn’t contain readable text. Export the request as a file instead."
                Empty -> "This QR code is empty. Scan the signing request from your other wallet."
                TooLarge -> "This QR code is too large. Use an animated QR code or export a request file."
            }
            QrScanPurpose.RecoveryPhrase -> when (this) {
                NonText -> "This QR code doesn’t contain readable words. Compact SeedQR isn’t supported; use a QR code with the words or a Standard SeedQR, or type the words."
                Empty -> "This QR code is empty. Scan a QR code that contains your recovery phrase."
                TooLarge -> "This QR code is too large to be a recovery phrase."
            }
        }
    }

    sealed interface Outcome {
        /** Hand [text] to the consumer **unchanged** (no trimming: the engine validates the raw text). */
        data class Accepted(val text: String) : Outcome
        data class Rejected(val issue: Issue) : Outcome
        data object Throttled : Outcome
    }

    private var last: Outcome? = null
    private var lastTimeMillis: Long? = null

    /**
     * Null → `Rejected(NonText)`; more than [MAXIMUM_BYTES] UTF-8 bytes → `Rejected(TooLarge)` (exactly the limit is
     * accepted); blank after trimming Swift whitespace and newlines → `Rejected(Empty)`; else `Accepted(value)`. The same
     * outcome within 1 s after the last delivered one (and not earlier than it) is `Throttled`, which does not move the
     * window, so a code held in view is offered again every second.
     */
    fun receive(value: String?, atMillis: Long): Outcome {
        val next: Outcome = when {
            value == null -> Outcome.Rejected(Issue.NonText)
            value.utf8Size() > MAXIMUM_BYTES -> Outcome.Rejected(Issue.TooLarge)
            value.trimWhitespacesAndNewlines().isEmpty() -> Outcome.Rejected(Issue.Empty)
            else -> Outcome.Accepted(value)
        }
        val previous = lastTimeMillis
        if (next == last && previous != null && atMillis >= previous && atMillis - previous < THROTTLE_MILLIS) return Outcome.Throttled
        last = next
        lastTimeMillis = atMillis
        return next
    }

    companion object {
        const val MAXIMUM_BYTES = 16_384
        const val THROTTLE_MILLIS = 1_000L
    }
}
