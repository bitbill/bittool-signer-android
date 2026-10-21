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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.SwitchRow
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.PhraseBreaks.WORD_JOINER
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phrase-based line breaking of switch subtitles and large titles (QA setup-15, regression-13, backlog B31): Japanese
 * and Korean lines break between phrases, on API 33+ through the platform (`LineBreak.Heading`, `WordBreak.Phrase`)
 * and below through word joiners ([PhraseBreaks]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class LineBreakingTest {
    @get:Rule
    val compose = createComposeRule()

    private val WJ = WORD_JOINER.toString()

    /** [phrase] with a word joiner between each two of its characters. */
    private fun joined(phrase: String): String = phrase.toList().joinToString(WORD_JOINER.toString())

    @Test
    fun japanesePhrasesAreKeptTogetherAndBreakOnlyBetweenPhrases() {
        assertEquals(
            joined("上級者向け。") + "「" + WJ + "25 " + joined("番目の") + joined("単語」を") + joined("追加します。"),
            PhraseBreaks.keepPhrasesTogether("上級者向け。「25 番目の単語」を追加します。"),
        )
        // "EVM ネット / ワーク" (QA regression-13): a katakana word never breaks; Latin words are left as they are.
        assertEquals(
            "Bitcoin" + WJ + "、EVM " + joined("ネットワーク、") + "TRON" + WJ + "、Solana に" + joined("対応します。"),
            PhraseBreaks.keepPhrasesTogether("Bitcoin、EVM ネットワーク、TRON、Solana に対応します。"),
        )
        assertEquals(joined("ウォレットを") + joined("作成しました"), PhraseBreaks.keepPhrasesTogether("ウォレットを作成しました"))
    }

    @Test
    fun koreanWordsAreKeptWholeAndBreakAtSpaces() {
        assertEquals(
            joined("고급") + " " + joined("사용자용") + ". \"25" + WJ + joined("번째") + " " + joined("단어") + "\"를 " +
                joined("추가합니다") + ".",
            PhraseBreaks.keepPhrasesTogether("고급 사용자용. \"25번째 단어\"를 추가합니다."),
        )
    }

    @Test
    fun otherLanguagesAndApi33AndLaterAreUnchanged() {
        val others = listOf("Advanced. Adds a “25th word”.", "高级选项。添加“第 25 个词”。", "متقدم. يضيف \"الكلمة الخامسة\".")
        for (text in others) {
            assertEquals(text, PhraseBreaks.keepPhrasesTogether(text))
        }
        val japanese = "上級者向け。「25 番目の単語」を追加します。"
        assertEquals(japanese, PhraseBreaks.forSdk(japanese, Build.VERSION_CODES.TIRAMISU))
        assertEquals(PhraseBreaks.keepPhrasesTogether(japanese), PhraseBreaks.forSdk(japanese, Build.VERSION_CODES.S_V2))
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
     * Below API 33 (QA regression-13 on API 31: "…を追加しま / す。"): at every width the subtitle wraps at, as long as a
     * line holds the longest phrase, the phrases "番目の", "単語」を" and "追加します。" stay on one line each.
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
        // Offsets in the drawn text of the characters of the subtitle (the drawn text may hold word joiners).
        val offsets = shown.indices.filter { shown[it] != WORD_JOINER }
        assertEquals(subtitle.length, offsets.size)
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
            val layout = compose.onNodeWithText(shown, useUnmergedTree = true).textLayout()
            if (layout.lineCount > 1) wrapped++
            sameLine(layout, "単語」を")
            sameLine(layout, "追加します。")
            sameLine(layout, "番目の")
        }
        assertTrue(wrapped > 10, "The widths cover wrapped subtitles")
    }
}
