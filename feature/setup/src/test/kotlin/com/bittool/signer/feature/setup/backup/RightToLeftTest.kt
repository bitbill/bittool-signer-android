package com.bittool.signer.feature.setup.backup

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.Look
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.ui.LinkButton
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Arabic (right to left) layout of the backup components (setup-backup §14.4): the recovery words stay left to right
 * and sit next to their number, and direction arrows point the reading direction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RightToLeftTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()
    private val words = RecoveryWords(Phrases.VECTOR_12.split(' '))

    /** How far the drawn line of a word ends from the end of its own box (its trailing gap, in px). */
    private fun SemanticsNodeInteraction.trailingGap(): Float {
        val node = fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        return node.size.width - layout.getLineRight(0)
    }

    @Test
    fun revealedWordsSitNextToTheirNumber() {
        compose.setContent {
            SetupTestHost(factory, Look.Rtl) {
                ProtectedRecoveryPhrase(words = words, session = remember { factory.privacySession() }, active = true)
            }
        }
        compose.onNodeWithTag("recovery.reveal").performClick()
        // The word (left to right) ends where its box ends, so it touches the 6 dp gap before its number on the right.
        val gap = compose.onAllNodesWithText("sausage", useUnmergedTree = true)[0].trailingGap()
        assertTrue(gap < 2f, "the word is drawn against its number, not at the far edge of the cell ($gap px away)")
    }

    /** "Recovery Cards →" points forward in the reading direction, which is to the left in Arabic. */
    @Test
    fun theRecoveryCardsArrowPointsTheReadingDirection() {
        compose.setContent {
            SetupTestHost(factory, Look.Rtl) {
                LinkButton(text = "", trailingIcon = SignerIcons.ArrowForward, onClick = {}, modifier = Modifier.testTag("link"))
            }
        }
        val pixels = compose.onNodeWithTag("link").captureToImage().toPixelMap()
        val background = pixels[0, 0]
        fun painted(x: Int, y: Int): Boolean {
            val c = pixels[x, y]
            return abs(c.red - background.red) + abs(c.green - background.green) + abs(c.blue - background.blue) > 0.3f
        }
        val columns = (0 until pixels.width).filter { x -> (0 until pixels.height).any { y -> painted(x, y) } }
        assertTrue(columns.isNotEmpty(), "the arrow is drawn")
        // The arrow head is the tallest part of the glyph: it is on the left when the arrow points left.
        val height = columns.associateWith { x -> (0 until pixels.height).count { y -> painted(x, y) } }
        val head = height.maxBy { it.value }.key
        val middle = (columns.first() + columns.last()) / 2f
        assertTrue(head < middle, "the arrow head is on the left (column $head, glyph ${columns.first()}–${columns.last()})")
    }
}
