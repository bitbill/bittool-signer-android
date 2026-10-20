package com.bittool.signer.core.data

import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText

/**
 * An [EngineException] raised by the **Kotlin host** of the engine (`:core:engine`), not by `signer.js`: its message
 * is one of the bridge messages of iOS `Core/SigningEngine.swift` (engine-contract.md §8.1), which the iOS catalogue
 * does not contain. The store rethrows it with a translatable [text] from `strings_data.xml` (DECISIONS.md §9.2,
 * gaps.md G6) and keeps the original as [cause]. Features never special-case it: it is an ordinary
 * [SignerException].
 */
class EngineHostException(
    /** The bridge code (`ENGINE_UNAVAILABLE`, `SCAN`, …), for diagnostics and tests only. */
    val code: String,
    text: UiText,
    cause: EngineException,
) : SignerException(text, cause)

/**
 * The one mapping every engine call of `DefaultSignerStore` goes through (SignerStore KDoc "Errors"). Engine-authored
 * errors (resolver, protocol and parser messages) are returned unchanged and shown verbatim, exactly like iOS.
 */
internal object EngineHostErrors {
    /** The default message the host gives an engine error that has none (iOS `SigningEngine.swift:84-89`). */
    private const val SIGNING_FAILED_MESSAGE = "This request could not be signed."

    private val bridgeMessages: Map<Pair<String, String>, Int> = mapOf(
        (EngineException.ENGINE_MISSING to "The bundled signing engine is unavailable. Reinstall BitTool Signer.") to
            R.string.data_engine_missing,
        (EngineException.ENGINE_INVALID to "The bundled signing engine could not be loaded.") to R.string.data_engine_invalid,
        (EngineException.ENGINE_UNAVAILABLE to "The offline signing engine could not be started.") to R.string.data_engine_unavailable,
        (EngineException.ENTROPY_UNAVAILABLE to "The engine’s secure entropy source could not be installed.") to
            R.string.data_engine_entropy_unavailable,
        (EngineException.ENGINE_FAILURE to "The offline engine could not process this request.") to R.string.data_engine_failure,
        (EngineException.UNSUPPORTED_OPERATION to "This signing operation is not supported.") to
            R.string.data_engine_unsupported_operation,
        (EngineException.REQUEST_TOO_LARGE to "This request exceeds the 1 MB offline signing limit.") to
            R.string.data_engine_request_too_large,
        (EngineException.SCAN to "The scan session ended. Scan again.") to R.string.data_engine_scan_ended,
    )

    /**
     * [error] as the store throws it. Bridge messages are matched by their exact code **and** English; the
     * default "This request could not be signed." is matched by its text alone, because the host substitutes it for
     * a missing message whatever the code (`EngineException.bounded`) and the engine bundle never authors it.
     */
    fun translate(error: EngineException): Exception {
        val id = bridgeMessages[error.code to error.message]
            ?: R.string.data_engine_signing_failed.takeIf { error.message == SIGNING_FAILED_MESSAGE }
            ?: return error
        return EngineHostException(error.code, UiText.Resource(id), error)
    }
}
