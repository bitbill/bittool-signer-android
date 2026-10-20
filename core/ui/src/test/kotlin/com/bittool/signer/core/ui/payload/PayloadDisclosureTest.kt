package com.bittool.signer.core.ui.payload

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** iOS `PayloadDisclosure` (`Design/PayloadDisclosure.swift`): limits, break opportunities, toggle and copy. */
@RunWith(RobolectricTestRunner::class)
class PayloadDisclosureTest {
    @get:Rule
    val compose = createComposeRule()

    private val zwsp = PayloadText.ZERO_WIDTH_SPACE

    @Test
    fun shortPayloadsAreShownUnchanged() {
        val text = "{\n  \"type\" : \"crypto-account\"\n}"
        assertEquals(PayloadText.Shown(text, truncated = false), PayloadText.display(text))
        // Long lines get no break opportunities while the whole text stays within 2,000 UTF-8 bytes.
        val long = "A".repeat(1_500)
        assertEquals(long, PayloadText.display(long).text)
    }

    @Test
    fun longRunsGetAZeroWidthSpaceEverySixtyFourCharacters() {
        val base64 = "Q".repeat(200) + " " + "R".repeat(70) + "\n" + "short line"
        val padded = base64 + "\n" + "x".repeat(2_000)
        val shown = PayloadText.display(padded).text
        val firstLine = shown.substringBefore('\n')
        // 200 characters: breaks after 64, 128 and 192; the space resets the run, 70 → one break after 64.
        assertEquals(4, firstLine.count { it == zwsp })
        assertEquals(64, firstLine.indexOf(zwsp))
        assertEquals("short line", shown.split('\n')[1], "lines up to 120 characters are untouched")
        assertEquals(padded, shown.replace(zwsp.toString(), ""), "only break opportunities are added")
    }

    @Test
    fun payloadsOverThirtyThousandCharactersAreCut() {
        val huge = "B".repeat(40_000)
        val shown = PayloadText.display(huge)
        assertTrue(shown.truncated)
        assertTrue(shown.text.endsWith("\n…"))
        assertEquals(PayloadText.DISPLAY_LIMIT, shown.text.removeSuffix("\n…").replace(zwsp.toString(), "").length)
        assertFalse(PayloadText.display("B".repeat(30_000)).truncated)
    }

    @Test
    fun theToggleRevealsThePayloadAndCopyTakesEverything() {
        val huge = "C".repeat(31_000)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                PayloadDisclosure(title = "Payload (JSON)", text = huge, notes = listOf("A note"), tag = "signature.payload")
            }
        }
        val toggle = compose.onNodeWithTag("signature.payloadToggle")
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Hidden"))
        compose.onNodeWithTag("signature.payload").assertDoesNotExist()
        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Shown"))
        compose.onNodeWithTag("signature.payload").assertExists()
        compose.onNodeWithText("A note").assertExists()
        compose.onNodeWithText("Showing the first 30,000 of 31,000 characters. Copy includes everything.").assertExists()
        compose.onNodeWithTag("signature.payloadCopy").performClick()
        compose.onNodeWithText("Copied").assertExists()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals(huge, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
}
