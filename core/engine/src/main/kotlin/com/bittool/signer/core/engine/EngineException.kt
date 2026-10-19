package com.bittool.signer.core.engine

/**
 * A bounded, engine-authored error. Only controlled `EngineError`/`ProtocolError` messages cross the native boundary
 * (code ≤ 80 chars, message ≤ 400 chars, like iOS); unexpected failures carry a generic message without input text.
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
            (code ?: SIGNING_FAILED).take(80),
            (message ?: "This request could not be signed.").take(400),
        )
    }
}
