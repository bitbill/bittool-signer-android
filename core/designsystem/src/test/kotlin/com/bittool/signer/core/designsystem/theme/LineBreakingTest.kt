package com.bittool.signer.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.PhraseText
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.SwitchRow
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.PhraseBreaks.WORD_JOINER
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phrase-based line breaking of titles, running text and switch subtitles (QA setup-15, regression-13, backlog B31):
 * Japanese and Korean lines break between phrases, through the platform where it can (`LineBreak.Heading`,
 * `WordBreak.Phrase`; Japanese from API 33, Korean from 35) and below through word joiners ([PhraseBreaks]), which
 * accessibility never reads ([PhraseText]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class LineBreakingTest {
    @get:Rule
    val compose = createComposeRule()

    /** The places a line may break in [joined]: the phrases between them (spaces belong to the phrase before). */
    private fun phrases(joined: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        joined.forEachIndexed { index, c ->
            if (c == WORD_JOINER) return@forEachIndexed
            val previous = joined.getOrNull(index - 1)
            val latin = previous != null && previous.isAsciiLetterOrDigit() && c.isAsciiLetterOrDigit()
            val breakable = previous != null && previous != WORD_JOINER && !previous.isWhitespace() && !c.isWhitespace() && !latin
            if (breakable || (previous != null && previous.isWhitespace() && !c.isWhitespace())) {
                result += current.toString()
                current.clear()
            }
            current.append(c)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /** Latin words never break inside (UAX #14), joiners or not. */
    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun below33(text: String) = PhraseBreaks.forSdk(text, Build.VERSION_CODES.S_V2)

    @Test
    fun japanesePhrasesAreKeptTogetherAndBreakOnlyBetweenPhrases() {
        // The setup titles (ja, the iOS catalogue).
        assertEquals(listOf("ウォレットを", "作成しました"), phrases(below33("ウォレットを作成しました")))
        assertEquals(listOf("ウォレットを", "インポートしました"), phrases(below33("ウォレットをインポートしました")))
        assertEquals(listOf("パスワードを", "再設定しました"), phrases(below33("パスワードを再設定しました")))
        assertEquals(listOf("フレーズを", "バックアップ"), phrases(below33("フレーズをバックアップ")))
        assertEquals(listOf("この", "ウォレットは", "ロック解除できません"), phrases(below33("このウォレットはロック解除できません")))
        // Brackets, particles after them, punctuation, spaces and Latin words (the SwitchRow subtitle of B31).
        assertEquals(
            listOf("上級者向け。", "「25 ", "番目の", "単語」を", "追加します。"),
            phrases(below33("上級者向け。「25 番目の単語」を追加します。")),
        )
        // "EVM ネット / ワーク" (QA regression-13): a katakana word never breaks.
        assertEquals(
            listOf("Bitcoin、", "EVM ", "ネットワーク、", "TRON、", "Solana ", "に", "対応します。"),
            phrases(below33("Bitcoin、EVM ネットワーク、TRON、Solana に対応します。")),
        )
        assertEquals(listOf("BIP39 ", "パスフレーズ"), phrases(below33("BIP39 パスフレーズ")))
        assertEquals(listOf("すごーい"), phrases(below33("すごーい")), "the long-vowel mark continues its run")
        // A hiragana phrase after a full stop starts a new phrase.
        assertEquals(listOf("完了。", "このままで"), phrases(below33("完了。このままで")))
    }

    @Test
    fun koreanWordsAreKeptWholeAndBreakAtSpaces() {
        assertEquals(listOf("비밀번호를 ", "재설정했습니다"), phrases(below33("비밀번호를 재설정했습니다")))
        assertEquals(listOf("이 ", "지갑은 ", "잠금 ", "해제할 ", "수 ", "없습니다"), phrases(below33("이 지갑은 잠금 해제할 수 없습니다")))
        assertEquals(
            listOf("고급 ", "사용자용. ", "\"25번째 ", "단어\"를 ", "추가합니다."),
            phrases(below33("고급 사용자용. \"25번째 단어\"를 추가합니다.")),
        )
    }

    /**
     * Android 13 and 14 have the phrase style for Japanese, but do not promise Korean `keep-all` with it: a Korean text
     * keeps its words through the joiners up to API 34 (the same breaks where the platform already keeps them), a
     * Japanese one is left to the platform from API 33.
     */
    @Test
    fun koreanKeepsItsWordsUpToApi34() {
        for (sdk in listOf(Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            assertEquals(
                listOf("이 ", "지갑은 ", "잠금 ", "해제할 ", "수 ", "없습니다"),
                phrases(PhraseBreaks.forSdk("이 지갑은 잠금 해제할 수 없습니다", sdk)),
                "API $sdk",
            )
            assertEquals("ウォレットを作成しました", PhraseBreaks.forSdk("ウォレットを作成しました", sdk), "API $sdk")
        }
        assertEquals("이 지갑은 잠금 해제할 수 없습니다", PhraseBreaks.forSdk("이 지갑은 잠금 해제할 수 없습니다", PhraseBreaks.KOREAN_PHRASES_SDK))
    }

    @Test
    fun otherLanguagesAndNewerVersionsAreDrawnAsWritten() {
        val others = listOf(
            "Wallet Created", "Advanced. Adds a “25th word”.", "钱包已创建", "高级选项。添加“第 25 个词”。", "Кошелёк создан",
            "تم إنشاء المحفظة", "متقدم. يضيف \"الكلمة الخامسة\".", "",
        )
        for (text in others) {
            assertEquals(text, below33(text))
            assertEquals(text, PhraseBreaks.keepPhrasesTogether(text))
        }
        assertEquals("ウォレットを作成しました", PhraseBreaks.forSdk("ウォレットを作成しました", Build.VERSION_CODES.TIRAMISU))
        assertEquals("지갑을 만들었습니다", PhraseBreaks.forSdk("지갑을 만들었습니다", Build.VERSION_CODES.BAKLAVA))
        // The joiners are the only change: the reader's characters stay, and a text drawn twice gets no more of them.
        val japanese = "上級者向け。「25 番目の単語」を追加します。"
        assertEquals(japanese, below33(japanese).replace(WORD_JOINER.toString(), ""))
        assertEquals(below33(japanese), below33(below33(japanese)))
        assertEquals(PhraseBreaks.keepPhrasesTogether(japanese), below33(japanese))
    }

    private fun content(body: @Composable () -> Unit) {
        compose.setContent { SignerTheme(darkTheme = false, applySystemBarAppearance = false) { body() } }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun theSwitchSubtitleBreaksByPhraseAndTheLargeTitleLikeAHeading() {
        content {
            SignerTopAppBar(title = "ウォレット", large = true)
            SwitchRow(
                title = "BIP39 パスフレーズ",
                icon = null,
                checked = false,
                onCheckedChange = {},
                subtitle = "上級者向け。「25 番目の単語」を追加します。",
            )
        }
        val subtitle = compose.onNodeWithText("上級者向け。「25 番目の単語」を追加します。", useUnmergedTree = true).textLayout()
        assertEquals(LineBreak.WordBreak.Phrase, subtitle.layoutInput.style.lineBreak.wordBreak)
        assertEquals(LineBreak.Strategy.Simple, subtitle.layoutInput.style.lineBreak.strategy, "Latin text breaks as before")
        // The expanded and the collapsed row of the large bar.
        val titles = compose.onAllNodesWithText("ウォレット", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, titles.size)
        for (title in titles) {
            val layouts = mutableListOf<TextLayoutResult>()
            title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals(LineBreak.Heading, layouts.single().layoutInput.style.lineBreak)
        }
    }

    /**
     * Below API 33 [PhraseText] draws the joiners but TalkBack, the tree and the e2e suite read the text as written: the
     * node keeps its own semantics (here a heading, a tag and the text layout), only its text is the written one.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun belowApi33PhraseTextDrawsTheJoinersButReadsAsWritten() {
        val title = "ウォレットを作成しました"
        content {
            PhraseText(title, SignerTheme.typography.largeTitle.headingLineBreaks(), Modifier.testTag("title").semantics { heading() })
        }
        val node = compose.onNodeWithTag("title", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf(title), node.config[SemanticsProperties.Text].map { it.text })
        assertTrue(SemanticsProperties.Heading in node.config)
        val drawn = compose.onNodeWithTag("title", useUnmergedTree = true).textLayout().layoutInput.text.text
        assertEquals(PhraseBreaks.keepPhrasesTogether(title), drawn, "the drawn copy carries the joiners")
        assertTrue(WORD_JOINER in drawn)
        compose.onNodeWithText(drawn, useUnmergedTree = true).assertDoesNotExist()
    }

    /** From API 33 a Japanese text is drawn as written (the platform breaks by phrase), and English everywhere. */
    @Test
    fun fromApi33PhraseTextDrawsTheTextAsWritten() {
        content {
            PhraseText("ウォレットを作成しました", SignerTheme.typography.largeTitle.headingLineBreaks(), Modifier.testTag("ja"))
            PhraseText("Wallet Created", SignerTheme.typography.body.phraseLineBreaks(), Modifier.testTag("en"))
        }
        for ((tag, text) in listOf("ja" to "ウォレットを作成しました", "en" to "Wallet Created")) {
            assertEquals(text, compose.onNodeWithTag(tag, useUnmergedTree = true).textLayout().layoutInput.text.text)
            assertFalse(WORD_JOINER in compose.onNodeWithTag(tag, useUnmergedTree = true).textLayout().layoutInput.text.text)
        }
    }

    /**
     * Below API 33 (QA regression-13 on API 31: "…を追加しま / す。"): at every width the subtitle wraps at, as long as a
     * line holds the longest phrase, the phrases "番目の", "単語」を" and "追加します。" stay on one line each, and the
     * subtitle reads as written.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun belowApi33TheSwitchSubtitleStillBreaksOnlyBetweenPhrases() {
        val subtitle = "上級者向け。「25 番目の単語」を追加します。"
        val shown = phraseBreakable(subtitle)
        var width by mutableStateOf(400.dp)
        content {
            Box(Modifier.width(width)) {
                SwitchRow(
                    title = "BIP39 パスフレーズ",
                    icon = SignerIcons.ShieldLock,
                    checked = false,
                    onCheckedChange = {},
                    subtitle = subtitle,
                )
            }
        }
        // Offsets in the drawn text of the characters of the subtitle (the drawn text holds word joiners).
        val offsets = shown.indices.filter { shown[it] != WORD_JOINER }
        assertEquals(subtitle.length, offsets.size)
        assertTrue(offsets.size < shown.length, "the drawn copy carries joiners on API 31")
        fun sameLine(layout: TextLayoutResult, phrase: String) {
            val first = subtitle.indexOf(phrase)
            assertTrue(first >= 0)
            val start = offsets[first]
            val end = offsets[first + phrase.length - 1]
            assertEquals(
                layout.getLineForOffset(start),
                layout.getLineForOffset(end),
                "\"$phrase\" is split across lines at $width",
            )
        }
        var wrapped = 0
        for (w in 240..440 step 4) {
            width = w.dp
            compose.waitForIdle()
            val layout = compose.onNodeWithText(subtitle, useUnmergedTree = true).textLayout()
            assertEquals(shown, layout.layoutInput.text.text)
            if (layout.lineCount > 1) wrapped++
            sameLine(layout, "単語」を")
            sameLine(layout, "追加します。")
            sameLine(layout, "番目の")
        }
        assertTrue(wrapped > 10, "The widths cover wrapped subtitles")
    }
}
