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
import androidx.compose.ui.unit.Dp
import com.bittool.signer.core.designsystem.theme.SignerTheme
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
 * QA setup-15 / regression-13: below API 33 (no phrase word break) the setup large titles still break at phrase
 * boundaries in Japanese and Korean ("ウォレットを作成しま / した" on the API 31 QA phone), through invisible word
 * joiners; from API 33, and in every other language, the text is drawn as written.
 */
@RunWith(RobolectricTestRunner::class)
class HeadingPhrasesTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()

    /** The places a line may break in [joined]: the phrases between them (spaces belong to the phrase before). */
    private fun phrases(joined: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        joined.forEachIndexed { index, c ->
            if (c == HeadingPhrases.WORD_JOINER) return@forEachIndexed
            val previous = joined.getOrNull(index - 1)
            val breakable = previous != null && previous != HeadingPhrases.WORD_JOINER && !previous.isWhitespace() && !c.isWhitespace()
            if (breakable || (previous != null && previous.isWhitespace() && !c.isWhitespace())) {
                result += current.toString()
                current.clear()
            }
            current.append(c)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun below33(text: String) = HeadingPhrases.keepTogether(text, sdk = Build.VERSION_CODES.S_V2)

    @Test
    fun japaneseTitlesBreakBetweenPhrasesOnly() {
        // The setup titles (ja, the iOS catalogue).
        assertEquals(listOf("ウォレットを", "作成しました"), phrases(below33("ウォレットを作成しました")))
        assertEquals(listOf("ウォレットを", "インポートしました"), phrases(below33("ウォレットをインポートしました")))
        assertEquals(listOf("パスワードを", "再設定しました"), phrases(below33("パスワードを再設定しました")))
        assertEquals(listOf("フレーズを", "バックアップ"), phrases(below33("フレーズをバックアップ")))
        assertEquals(listOf("この", "ウォレットは", "ロック解除できません"), phrases(below33("このウォレットはロック解除できません")))
        // Brackets, particles after them, punctuation, spaces and Latin words.
        assertEquals(
            listOf("上級者向け。", "「25 ", "番目の", "単語」を", "追加します。"),
            phrases(below33("上級者向け。「25 番目の単語」を追加します。")),
        )
        assertEquals(listOf("BIP39 ", "パスフレーズ"), phrases(below33("BIP39 パスフレーズ")))
        assertEquals(listOf("すごーい"), phrases(below33("すごーい")), "the long-vowel mark continues its run")
    }

    @Test
    fun koreanTitlesBreakBetweenWordsOnly() {
        assertEquals(listOf("비밀번호를 ", "재설정했습니다"), phrases(below33("비밀번호를 재설정했습니다")))
        assertEquals(listOf("이 ", "지갑은 ", "잠금 ", "해제할 ", "수 ", "없습니다"), phrases(below33("이 지갑은 잠금 해제할 수 없습니다")))
    }

    @Test
    fun otherLanguagesAndNewerVersionsAreDrawnAsWritten() {
        for (text in listOf("Wallet Created", "钱包已创建", "Кошелёк создан", "تم إنشاء المحفظة", "")) {
            assertEquals(text, below33(text))
        }
        assertEquals("ウォレットを作成しました", HeadingPhrases.keepTogether("ウォレットを作成しました", sdk = Build.VERSION_CODES.TIRAMISU))
        assertEquals("지갑을 만들었습니다", HeadingPhrases.keepTogether("지갑을 만들었습니다", sdk = Build.VERSION_CODES.BAKLAVA))
        // The joiners are the only change: the reader's characters stay.
        assertEquals("ウォレットを作成しました", below33("ウォレットを作成しました").replace(HeadingPhrases.WORD_JOINER.toString(), ""))
    }

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
        var drawn: TextLayoutResult? = null
        compose.setContent {
            SetupTestHost(factory) {
                val style = SignerTheme.typography.largeTitle.headingLineBreaks()
                Column {
                    Text(title, style = style, softWrap = false, onTextLayout = { natural = it }, modifier = Modifier.wrapContentWidth(unbounded = true))
                    width?.let { w ->
                        LargeTitle(title, modifier = Modifier.width(w).testTag("title"))
                        // What LargeTitle draws (its text node is hidden behind the title's own semantics), and the
                        // same title without the joiners.
                        Text(HeadingPhrases.keepTogether(title), style = style, onTextLayout = { drawn = it }, modifier = Modifier.width(w))
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
        val layout = checkNotNull(drawn)
        val debug = buildString {
            append("single=$single width=$width plainEnds=${(0 until withoutJoiners.lineCount).map { withoutJoiners.getLineEnd(it) }} ")
            append("drawnEnds=${(0 until layout.lineCount).map { layout.getLineEnd(it) }} drawnLineWidths=${(0 until layout.lineCount).map { layout.getLineRight(it) - layout.getLineLeft(it) }} ")
            append("plainLineWidths=${(0 until withoutJoiners.lineCount).map { withoutJoiners.getLineRight(it) - withoutJoiners.getLineLeft(it) }} box=${layout.size}")
        }
        assertEquals(2, layout.lineCount, debug)
        val firstLine = layout.layoutInput.text.text.substring(0, layout.getLineEnd(0)).replace(HeadingPhrases.WORD_JOINER.toString(), "")
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
}
