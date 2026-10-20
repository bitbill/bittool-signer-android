package com.bittool.signer.core.data

import com.bittool.signer.core.engine.EngineException
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The nine Kotlin-host messages are translated; everything the engine bundle authors stays verbatim (G6, §9.2). */
class EngineHostErrorsTest {
    @Test
    fun everyBridgeMessageMapsToItsResource() {
        val expected = listOf(
            Triple(EngineException.ENGINE_MISSING, "The bundled signing engine is unavailable. Reinstall BitTool Signer.", R.string.data_engine_missing),
            Triple(EngineException.ENGINE_INVALID, "The bundled signing engine could not be loaded.", R.string.data_engine_invalid),
            Triple(EngineException.ENGINE_UNAVAILABLE, "The offline signing engine could not be started.", R.string.data_engine_unavailable),
            Triple(EngineException.ENTROPY_UNAVAILABLE, "The engine’s secure entropy source could not be installed.", R.string.data_engine_entropy_unavailable),
            Triple(EngineException.ENGINE_FAILURE, "The offline engine could not process this request.", R.string.data_engine_failure),
            Triple(EngineException.UNSUPPORTED_OPERATION, "This signing operation is not supported.", R.string.data_engine_unsupported_operation),
            Triple(EngineException.REQUEST_TOO_LARGE, "This request exceeds the 1 MB offline signing limit.", R.string.data_engine_request_too_large),
            Triple(EngineException.SCAN, "The scan session ended. Scan again.", R.string.data_engine_scan_ended),
            Triple(EngineException.SIGNING_FAILED, "This request could not be signed.", R.string.data_engine_signing_failed),
        )
        for ((code, message, id) in expected) {
            val source = EngineException(code, message)
            val translated = assertIs<EngineHostException>(EngineHostErrors.translate(source), code)
            assertEquals(UiText.Resource(id), translated.text)
            assertEquals(code, translated.code)
            assertSame(source, translated.cause)
        }
    }

    @Test
    fun theDefaultMessageIsHostTextWhateverTheCode() {
        // The host substitutes it for a missing message (EngineException.bounded, SigningEngine.swift:84-89).
        val translated = EngineHostErrors.translate(EngineException("RISK", "This request could not be signed."))
        assertEquals(UiText.Resource(R.string.data_engine_signing_failed), assertIs<EngineHostException>(translated).text)
    }

    @Test
    fun engineAuthoredErrorsStayVerbatim() {
        listOf(
            EngineException("SIGNER", "The decrypted wallet does not match the signing plan"),
            EngineException("QR_PROTOCOL", "Unsupported signing UR type"),
            EngineException("SCAN", "No scan session is active."),
            // A bridge message under another code is not the bridge's.
            EngineException("QR_PROTOCOL", "The scan session ended. Scan again."),
        ).forEach { assertSame(it, EngineHostErrors.translate(it)) }
    }
}
