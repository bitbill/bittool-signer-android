package com.bittool.signer.core.engine

import java.text.BreakIterator

/**
 * A bounded, engine-authored error. Only controlled `EngineError`/`ProtocolError` messages cross the native boundary
 * (code ≤ 80, message ≤ 400 grapheme clusters, like iOS `String.prefix`); unexpected failures carry a generic message
 * without input text.
 */
class EngineException(val code: String, override val message: String) : Exception(message) {
    companion object {
        const val ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE"
        const val ENGINE_INVALID = "ENGINE_INVALID"
        const val ENGINE_MISSING = "ENGINE_MISSING"
        const val ENGINE_FAILURE = "ENGINE_FAILURE"
        const val ENTROPY_UNAVAILABLE = "ENTROPY_UNAVAILABLE"
        const val UNSUPPORTED_OPERATION = "UNSUPPORTED_OPERATION"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE"
        const val SCAN = "SCAN"
        const val SIGNING_FAILED = "SIGNING_FAILED"

        internal fun bounded(code: String?, message: String?): EngineException = EngineException(
            prefixGraphemes(code ?: SIGNING_FAILED, 80),
            prefixGraphemes(message ?: "This request could not be signed.", 400),
        )

        internal fun prefixGraphemes(text: String, limit: Int): String {
            val boundaries = BreakIterator.getCharacterInstance()
            boundaries.setText(text)
            var end = 0
            repeat(limit) {
                val next = boundaries.next()
                if (next == BreakIterator.DONE) return text
                end = next
            }
            return text.substring(0, end)
        }
    }
}
