package com.bittool.signer.core.designsystem.theme

import android.os.Build
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak

/**
 * Line breaking for titles (large titles, headings of a few words): balanced lines and, on API 33+, breaks at phrase
 * boundaries in Japanese and Korean (`LineBreak.Heading`), so "ウォレットを作成しました" does not leave "した" alone on its
 * second line (QA setup-15, regression-13). Below API 33 the platform cannot break by phrase: draw the text through
 * [phraseBreakable] for the same result.
 */
fun TextStyle.headingLineBreaks(): TextStyle = copy(lineBreak = LineBreak.Heading)

/**
 * Line breaking for short running text in a narrow column (a switch row's subtitle): the default breaking, but at
 * phrase boundaries in Japanese and Korean on API 33+ (`WordBreak.Phrase`), so "「25 番目の単語」を追加します。" does not
 * end with "す。" alone (backlog B31). Latin text breaks exactly as before. Below API 33 draw the text through
 * [phraseBreakable].
 */
fun TextStyle.phraseLineBreaks(): TextStyle = copy(lineBreak = LineBreak.Simple.copy(wordBreak = LineBreak.WordBreak.Phrase))

/**
 * [text] for a [headingLineBreaks] or [phraseLineBreaks] style on this device: unchanged on API 33+, where the platform
 * breaks Japanese and Korean at phrase boundaries itself; below, with word joiners that keep the phrases together
 * ([PhraseBreaks.keepPhrasesTogether]). Text without kana or Hangul (English, Chinese, Arabic…) never changes.
 */
fun phraseBreakable(text: String): String = PhraseBreaks.forSdk(text, Build.VERSION.SDK_INT)

/**
 * The pre-API-33 fallback of phrase-based line breaking (`LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE`, API 33+) for
 * Japanese and Korean, the two languages that style serves. Android before 13 may break Japanese between any two
 * characters and Korean between any two syllables, so a title or subtitle can end with one or two characters of a word
 * on its own line ("追加しま / す。", "ネット / ワーク"). A WORD JOINER (U+2060: zero width, not spoken, no line break on
 * either side) between the characters of one phrase leaves the line breaker only the phrase boundaries; a phrase longer
 * than a line is still broken where it must be (the platform's last-resort break), so nothing is ever clipped.
 *
 * - Japanese (text with kana): a phrase (bunsetsu) is a run of kanji, katakana or Latin letters with the hiragana,
 *   closing brackets and punctuation that follow it ("単語」を", "追加します。"). A break is left only where a phrase ends
 *   (after hiragana or closing punctuation) and a new one starts (kanji, katakana, a Latin letter or digit, an opening
 *   bracket), and at spaces.
 * - Korean (text with Hangul): words are kept whole and break at spaces (CSS `word-break: keep-all`).
 * - Anything else, Chinese included (which the platform's phrase style leaves alone too), is unchanged.
 *
 * The joiners stay in the drawn text only: on API 33+ (and in every test that runs there) the text is never changed.
 */
object PhraseBreaks {
    /** U+2060 WORD JOINER. */
    const val WORD_JOINER: Char = '\u2060'

    /** [text] as [phraseBreakable] draws it on API level [sdkInt]. Pure. */
    fun forSdk(text: String, sdkInt: Int): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) text else keepPhrasesTogether(text)

    /** [text] with a [WORD_JOINER] between every two characters of one Japanese phrase or Korean word. Pure. */
    fun keepPhrasesTogether(text: String): String {
        var japanese = false
        var korean = false
        text.forEachCodePoint { cp ->
            if (isKana(cp)) japanese = true
            if (isHangul(cp)) korean = true
        }
        if (!japanese && !korean) return text
        val out = StringBuilder(text.length * 2)
        var previous = -1
        text.forEachCodePoint { cp ->
            if (previous >= 0 && joins(previous, cp, japanese)) out.append(WORD_JOINER)
            out.appendCodePoint(cp)
            previous = cp
        }
        return out.toString()
    }

    /** Whether no line break may fall between [a] and [b]. */
    private fun joins(a: Int, b: Int, japanese: Boolean): Boolean {
        if (Character.isWhitespace(a) || Character.isWhitespace(b) || a == WORD_JOINER.code || b == WORD_JOINER.code) {
            return false
        }
        if (isHangul(a) || isHangul(b)) return Character.isLetterOrDigit(a) && Character.isLetterOrDigit(b)
        if (!japanese || !(isJapanese(a) || isJapanese(b))) return false
        return !(endsPhrase(a) && startsPhrase(b))
    }

    private fun endsPhrase(cp: Int): Boolean = isHiragana(cp) || cp in CLOSING

    private fun startsPhrase(cp: Int): Boolean = when {
        cp in OPENING -> true
        isHiragana(cp) || cp in CLOSING || cp in NOT_STARTING -> false
        else -> isKanji(cp) || isKatakana(cp) || Character.isLetterOrDigit(cp)
    }

    private fun isHiragana(cp: Int) = cp in 0x3041..0x309F
    private fun isKatakana(cp: Int) = cp in 0x30A0..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F
    private fun isKana(cp: Int) = isHiragana(cp) || isKatakana(cp)
    private fun isKanji(cp: Int) =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF || cp in 0x20000..0x3134F || cp in 0x3005..0x3007

    /** Kana, kanji and the CJK punctuation and full-width forms Japanese text uses. */
    private fun isJapanese(cp: Int) = isKana(cp) || isKanji(cp) || cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF

    private fun isHangul(cp: Int) =
        cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF

    /** Closing brackets and punctuation: they end the phrase before them. */
    private val CLOSING: Set<Int> = setOf(
        '、', '。', '，', '．', '・', '：', '；', '！', '？', '」', '』', '）', '〕', '］', '｝', '〉', '》', '】', '〙', '〗',
        '…', '‥', ',', '.', '!', '?', ':', ';', ')', ']', '}', '’', '”',
    ).mapTo(HashSet()) { it.code }

    /** Opening brackets: they start a phrase. */
    private val OPENING: Set<Int> = setOf(
        '「', '『', '（', '〔', '［', '｛', '〈', '《', '【', '〘', '〖', '(', '[', '{', '‘', '“',
    ).mapTo(HashSet()) { it.code }

    /** Characters that never start a phrase: the prolonged sound mark, small katakana and the iteration marks. */
    private val NOT_STARTING: Set<Int> = setOf(
        'ー', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヵ', 'ヶ', 'ヽ', 'ヾ', '々', '〻',
    ).mapTo(HashSet()) { it.code } + (0x31F0..0x31FF)

    private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
        var index = 0
        while (index < length) {
            val cp = codePointAt(index)
            action(cp)
            index += Character.charCount(cp)
        }
    }
}
