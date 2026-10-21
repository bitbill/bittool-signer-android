package com.bittool.signer.feature.setup.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import com.bittool.signer.core.designsystem.theme.PhraseBreaks
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.headingLineBreaks
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.SetupTestHost
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * QA setup-15 / regression-13: the setup large titles and running text break Japanese and Korean lines between
 * phrases also below API 33 (no phrase word break there: "ウォレットを作成しま / した" on the API 31 QA phone), through
 * the design system's `PhraseText` (word joiners in the drawn copy only); the phrases themselves are
 * `PhraseBreaks`' (core:designsystem `LineBreakingTest`).
 */
@RunWith(RobolectricTestRunner::class)
class LargeTitleTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()

    /**
     * On API 32 the drawn title of a narrow page wraps between phrases ("ウォレットを" / "インポートしました"),
     * where the same text without the joiners is cut inside a phrase, while TalkBack and the tree read the title as
     * written (no joiners), with its heading and test tag. The page is 70 % as wide as the title on one line.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2], qualifiers = "w800dp-h900dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onApi32TheDrawnTitleWrapsBetweenPhrasesAndReadsAsWritten() {
        val title = "ウォレットをインポートしました"
        val boundaries = setOf(6)
        var width by mutableStateOf<Dp?>(null)
        var natural: TextLayoutResult? = null
        var plain: TextLayoutResult? = null
        compose.setContent {
            SetupTestHost(factory) {
                val style = SignerTheme.typography.largeTitle.headingLineBreaks()
                Column {
                    Text(title, style = style, softWrap = false, onTextLayout = { natural = it }, modifier = Modifier.wrapContentWidth(unbounded = true))
                    width?.let { w ->
                        LargeTitle(title, modifier = Modifier.width(w).testTag("title"))
                        // The same title without the joiners.
                        Text(title, style = style, onTextLayout = { plain = it }, modifier = Modifier.width(w))
                    }
                }
            }
        }
        compose.waitForIdle()
        val single = checkNotNull(natural).size.width
        width = with(compose.density) { (single * 0.7f).toDp() }
        compose.waitForIdle()

        compose.onNodeWithTag("title").assert(
            SemanticsMatcher("reads the title as written, as a heading") {
                val texts = it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text }
                texts == listOf(title) && SemanticsProperties.Heading in it.config
            },
        )
        val withoutJoiners = checkNotNull(plain)
        assertTrue(withoutJoiners.lineCount >= 2, "precondition: the title wraps")
        assertTrue(withoutJoiners.getLineEnd(0) !in boundaries, "precondition: without the joiners a phrase is cut")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("title").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(PhraseBreaks.forSdk(title, Build.VERSION_CODES.S_V2), layout.layoutInput.text.text, "the drawn copy")
        assertEquals(LineBreak.Heading, layout.layoutInput.style.lineBreak)
        val debug = buildString {
            append("single=$single width=$width plainEnds=${(0 until withoutJoiners.lineCount).map { withoutJoiners.getLineEnd(it) }} ")
            append("drawnEnds=${(0 until layout.lineCount).map { layout.getLineEnd(it) }} box=${layout.size}")
        }
        assertEquals(2, layout.lineCount, debug)
        val firstLine = layout.layoutInput.text.text.substring(0, layout.getLineEnd(0)).replace(PhraseBreaks.WORD_JOINER.toString(), "")
        assertEquals("ウォレットを", firstLine, "the first line ends at a phrase boundary ($debug)")
    }

    @Test
    fun anEnglishTitleIsOneTextNode() {
        compose.setContent { SetupTestHost(factory) { LargeTitle("Wallet Created", modifier = Modifier.testTag("title")) } }
        compose.onNodeWithTag("title").assert(
            SemanticsMatcher("a heading with its own text layout") {
                SemanticsProperties.Heading in it.config &&
                    it.config.contains(SemanticsActions.GetTextLayoutResult)
            },
        )
    }

    /** A step's message (running text) breaks by phrase on API 33+ and reads as written below it. */
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun aStepMessageIsPhraseBrokenRunningText() {
        val message = "パスワードはこのデバイス上でこのウォレットを暗号化します。"
        compose.setContent {
            SetupTestHost(factory) {
                BodyText(message, style = SignerTheme.typography.body, color = SignerTheme.colors.secondaryLabel, modifier = Modifier.testTag("message"))
            }
        }
        val node = compose.onNodeWithTag("message").fetchSemanticsNode()
        assertEquals(listOf(message), node.config[SemanticsProperties.Text].map { it.text })
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(LineBreak.WordBreak.Phrase, layouts.single().layoutInput.style.lineBreak.wordBreak)
        assertEquals(PhraseBreaks.forSdk(message, Build.VERSION_CODES.S_V2), layouts.single().layoutInput.text.text)
    }
}
