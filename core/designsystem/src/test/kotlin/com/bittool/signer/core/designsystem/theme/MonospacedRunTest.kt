package com.bittool.signer.core.designsystem.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import kotlin.test.assertEquals
import org.junit.Test

/**
 * QA regression-10: the monospaced family of a "Fingerprint …" line sits on the value itself, a text run of its own, so
 * after an Arabic word and the value's isolate the value keeps its monospaced face.
 */
class MonospacedRunTest {
    @Test
    fun theValueAloneCarriesTheMonospacedFamily() {
        val sentence = "البصمة \u2068F7FB19CD\u2069"
        val line = monospacedRun(sentence, "F7FB19CD")
        assertEquals(sentence, line.text)
        assertEquals(listOf(AnnotatedString.Range(SpanStyle(fontFamily = SignerMonospace), 8, 16)), line.spanStyles)
        assertEquals("F7FB19CD", line.text.substring(8, 16), "Not the isolates around it")

        val english = monospacedRun("Fingerprint 73C5DA0A", "73C5DA0A")
        assertEquals(listOf(AnnotatedString.Range(SpanStyle(fontFamily = SignerMonospace), 12, 20)), english.spanStyles)
    }

    @Test
    fun textWithoutTheValueHasNoSpan() {
        assertEquals(emptyList(), monospacedRun("Fingerprint", "73C5DA0A").spanStyles)
        assertEquals(emptyList(), monospacedRun("Fingerprint", "").spanStyles)
    }
}
