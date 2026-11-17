package com.bittool.signer.core.engine

import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.toUiText
import kotlin.test.assertEquals
import org.junit.Test

/**
 * iOS Revision 15 (`SigningEngineError.errorDescription` → `ReviewText.sentence`): a refusal the engine wrote reaches
 * every failure display as [UiText.Engine], translated only when drawn, while [EngineException.message] stays the
 * engine's English for code and tests.
 */
class EngineErrorTextTest {
    @Test
    fun anEngineRefusalIsTranslatedWhenDrawnAndStaysEnglishInTheException() {
        val english = "This message is 1500 bytes. BitTool Signer signs messages of up to 1024 bytes, which is far more than a " +
            "sign-in or ownership message needs; anything larger is refused because it is usually transaction data in disguise."
        val error = EngineException("MESSAGE", english)
        assertEquals(english, error.message)
        assertEquals(UiText.Engine(english), error.toUiText())
    }
}
