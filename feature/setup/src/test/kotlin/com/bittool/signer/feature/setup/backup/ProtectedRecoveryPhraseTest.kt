package com.bittool.signer.feature.setup.backup

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.model.BackupVerification
import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.RecoveryWords
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Port of iOS `RecoveryPrivacyViewTests` (2 cases, presentation through the real UI) plus the reveal rules of
 * setup-backup §8.1–§8.6: concealed words are not composed at all, the tap is the consent, a screenshot conceals behind
 * a warning, the QR code alone does not count as having seen the words, and the cards do.
 */
@RunWith(RobolectricTestRunner::class)
class ProtectedRecoveryPhraseTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()
    private val words = RecoveryWords(Phrases.VECTOR_12.split(' '))

    private lateinit var session: RecoveryPrivacySession

    private fun showPhrase(onReveal: () -> Unit = {}) {
        compose.setContent {
            SetupTestHost(factory) {
                session = remember { factory.privacySession() }
                ProtectedRecoveryPhrase(words = words, session = session, active = true, onReveal = onReveal)
            }
        }
    }

    private fun assertNoWordComposed() {
        for (word in words.list.toSet()) {
            assertEquals(0, compose.onAllNodesWithText(word, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size, "no word in the tree")
        }
        compose.onNodeWithTag("recovery.word.0").assertDoesNotExist()
    }

    /** iOS `testScreenshotNotificationPresentsWarningThroughSwiftUI`. */
    @Test
    fun aScreenshotNotificationPresentsTheWarning() {
        showPhrase()
        compose.waitForIdle()
        factory.monitor.takeScreenshot()
        compose.onNodeWithText("Screenshot Detected").assertExists()
        assertFalse(session.isRevealed.value)
        assertEquals(RecoveryPrivacySession.Prompt.Screenshot, session.prompt.value)
    }

    /** iOS `testTapRevealsWithoutAConfirmationAndAScreenshotConcealsBehindTheWarning`. */
    @Test
    fun aTapRevealsWithoutAConfirmationAndAScreenshotConcealsBehindTheWarning() {
        var reveals = 0
        showPhrase(onReveal = { reveals++ })
        assertNoWordComposed()
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.onNodeWithTag("recovery.word.0").assertExists()
        compose.onNodeWithTag("recovery.word.0").assert(hasText(words.list[0], substring = true))
        compose.onNodeWithText("Screenshot Detected").assertDoesNotExist()
        assertEquals(1, reveals)

        factory.monitor.takeScreenshot()
        compose.onNodeWithText("Screenshot Detected").assertExists()
        assertNoWordComposed()
        assertEquals(RecoveryPrivacySession.Prompt.Screenshot, session.prompt.value, "presentation must not clear the gate")
        compose.onNodeWithTag("recovery.reveal").performClick()
        assertFalse(session.isRevealed.value, "nothing shows again until the warning is acknowledged")

        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Screenshot Detected").assertDoesNotExist()
        assertFalse(session.isRevealed.value, "acknowledging never reveals by itself")
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.onNodeWithTag("recovery.word.0").assertExists()
    }

    @Test
    fun pausingConcealsWithoutAPromptAndHideConcealsAtOnce() {
        showPhrase()
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.runOnIdle { factory.monitor.pause() }
        assertNoWordComposed()
        compose.onNodeWithText("Screenshot Detected").assertDoesNotExist()
        compose.runOnIdle { factory.monitor.resume() }
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.onNodeWithTag("recovery.hide").performClick()
        assertNoWordComposed()
    }

    /**
     * Switching Words → QR Code conceals what is left (§8.1) at once: revealed words never fade out over the QR tab
     * (the new format fades in; the old one is gone within the transition's first frames).
     */
    @Test
    fun switchingFormatsTakesRevealedWordsAwayInTheSameFrame() {
        compose.setContent { SetupTestHost(factory) { RecoveryBackupPanel(words = words) } }
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.onNodeWithTag("recovery.word.0").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("QR Code").performClick()
        // A few frames (the switch, then the transition's first frames): far less than the fade-in.
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        assertNoWordComposed()
        compose.onNodeWithTag("backup.qr.reveal").assertExists()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Words").performClick()
        compose.onNodeWithTag("recovery.reveal").assertExists()
        assertNoWordComposed()
    }

    @Test
    fun theRevealedGridMarksTheViewSensitiveAndNamesEachCell() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SetupTestHost(factory) {
                session = remember { factory.privacySession() }
                ProtectedRecoveryPhrase(words = words, session = session, active = true)
            }
        }
        compose.runOnIdle { assertFalse(view.isAccessibilityDataSensitive) }
        compose.onNodeWithTag("recovery.reveal").assert(
            SemanticsMatcher("labelled Show recovery phrase") {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.firstOrNull()?.startsWith("Show recovery phrase") == true
            },
        )
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.runOnIdle { assertTrue(view.isAccessibilityDataSensitive, "revealed words are accessibility-sensitive") }
        compose.onNodeWithTag("recovery.word.11").assertExists()
    }

    @Test
    fun revealingOnlyTheQrCodeDoesNotCountButTheCardsDo() {
        var reveals = 0
        compose.setContent {
            SetupTestHost(factory) { RecoveryBackupPanel(words = words, onReveal = { reveals++ }) }
        }
        compose.onNodeWithText("QR Code").performClick()
        compose.onNodeWithTag("backup.qr").assertDoesNotExist()
        compose.onNodeWithTag("backup.qr.reveal").performClick()
        compose.onNodeWithTag("backup.qr").assertExists()
        compose.runOnIdle { assertEquals(0, reveals, "the QR code is not the written backup") }
        compose.onNodeWithTag("backup.qr.hide").performClick()
        compose.onNodeWithTag("backup.qr").assertDoesNotExist()

        compose.onNodeWithText("Words").performClick()
        compose.onNodeWithTag("recovery.reveal").assertExists()
        compose.onNodeWithTag("backup.cards").performClick()
        compose.onNodeWithTag("cards.reveal").performClick()
        compose.onNodeWithTag("cards.card.0").assertExists()
        compose.onNodeWithTag("cards.card.0.word.0").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Word 1, not on this card")),
        )
        compose.onNodeWithTag("cards.card.1.word.0").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Word 1, ${words.list[0]}")),
        )
        compose.onNodeWithText("With a 12-word phrase", substring = true).assertExists()
        compose.runOnIdle { assertEquals(1, reveals, "showing the words on the cards counts as revealing") }
    }

    @Test
    fun theVerificationBoardDescribesItsBlanksAndChips() {
        val initial = BackupVerification.create(words.list, Random(3))
        var verification by mutableStateOf(initial)
        compose.setContent {
            SetupTestHost(factory) {
                Column {
                    BackupVerificationGrid(verification, onSelect = { verification = verification.select(it) }, onClear = { verification = verification.clear(it) })
                    BackupVerificationChips(verification, onPlace = { id -> verification.place(id)?.let { verification = it } })
                }
            }
        }
        val first = initial.positions.first()
        compose.onNodeWithTag("verification.slot.$first").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Empty, next to fill"))
        compose.onNodeWithTag("verification.slot.${initial.positions[1]}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Empty"))
        val masked = (0 until 12).first { !initial.isBlank(it) }
        compose.onNodeWithContentDescription("Word ${masked + 1}, hidden").assertExists()
        compose.onNodeWithTag("verification.chip.0").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Available")).assertIsEnabled()

        compose.onNodeWithTag("verification.chip.0").performClick()
        compose.onNodeWithTag("verification.chip.0").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Placed")).assertIsNotEnabled()
        compose.onNodeWithTag("verification.slot.$first").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, initial.chips[0].word))
        compose.onNodeWithTag("verification.slot.${initial.positions[1]}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Empty, next to fill"))

        // Tapping a filled blank takes its word out and makes it the next to fill.
        compose.onNodeWithTag("verification.slot.$first").performClick()
        compose.onNodeWithTag("verification.slot.$first").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Empty, next to fill"))
        compose.onNodeWithTag("verification.chip.0").assertIsEnabled()
    }
}
