package com.bittool.signer.core.designsystem.theme

import android.os.Build
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import java.text.BreakIterator
import java.util.Locale

/**
 * Line breaking for titles (large titles, headings of a few words): balanced lines and, on API 33+, breaks at phrase
 * boundaries in Japanese and Korean (`LineBreak.Heading`), so "ウォレットを作成しました" does not leave "した" alone on its
 * second line (QA setup-15, regression-13). Below API 33 the platform cannot break by phrase: draw the text with
 * `PhraseText` (or through [phraseBreakable]) for the same result.
 */
fun TextStyle.headingLineBreaks(): TextStyle = copy(lineBreak = LineBreak.Heading)

/**
 * Line breaking for running text (a step's message, a footnote, a switch row's subtitle): the default breaking, but at
 * phrase boundaries in Japanese and Korean on API 33+ (`WordBreak.Phrase`), so "「25 番目の単語」を追加します。" does not
 * end with "す。" alone (backlog B31). Latin text breaks exactly as before. Below API 33 draw the text with `PhraseText`
 * (or through [phraseBreakable]).
 */
fun TextStyle.phraseLineBreaks(): TextStyle = copy(lineBreak = LineBreak.Simple.copy(wordBreak = LineBreak.WordBreak.Phrase))

/**
 * [text] for a [headingLineBreaks] or [phraseLineBreaks] style on this device ([PhraseBreaks.forSdk]): unchanged where
 * the platform breaks Japanese and Korean at phrase boundaries itself, else with word joiners that keep the phrases
 * together. Text without kana or Hangul (English, Chinese, Arabic…) never changes. The joiners are for the line breaker
 * only: show the result with `PhraseText`, which gives accessibility [text] as written.
 */
fun phraseBreakable(text: String): String = PhraseBreaks.forSdk(text, Build.VERSION.SDK_INT)

/**
 * Phrase-boundary line breaks where the platform has none (QA setup-15, regression-13, backlog B31): Japanese on
 * Android 8–12L, Korean up to Android 14.
 *
 * Titles and running text ask for phrase breaking ([headingLineBreaks], [phraseLineBreaks]), whose word break
 * (`LINE_BREAK_WORD_STYLE_PHRASE`) only exists from API 33. Below it a Japanese line breaks between any two characters
 * ("追加しま / す。", "ネット / ワーク") and a Korean one between any two syllables, where iOS keeps phrases (bunsetsu)
 * and Korean words whole. [forSdk] gives those versions the same result: it puts a WORD JOINER (U+2060: zero width, not
 * spoken, UAX #14 class WJ, "no break here") between the characters of each phrase, so lines can only break between
 * phrases and at spaces. A phrase longer than a whole line still breaks inside, as the line breaker always does, so
 * nothing is ever clipped. From API 33 a Japanese text, and from API 35 a Korean one, comes back unchanged: the
 * platform's own phrase breaking applies ([KOREAN_PHRASES_SDK]).
 *
 * Phrases, read from the characters themselves (no dictionary is needed for interface text), one grapheme cluster at a
 * time (a joiner never splits a character the reader sees):
 * - **Japanese** (text with kana): a phrase is optional opening brackets, a run of kanji, katakana, Latin letters or
 *   digits, the hiragana that follow it (okurigana, particles and endings: "ウォレットを", "作成しました"), then closing
 *   brackets and punctuation ("。", "、", "」"). A hiragana run that starts the text or follows punctuation is a phrase
 *   of its own ("この"). A particle right after a closing bracket stays with it ("」を"). The long-vowel mark continues
 *   the run before it ("インポート", "すごーい").
 * - **Korean** (text with Hangul): the words between spaces, as iOS and Korean typesetting break them (`keep-all`).
 * - Anything else, Chinese included (which the platform's phrase style leaves alone too), is unchanged.
 */
object PhraseBreaks {
    /** U+2060 WORD JOINER. */
    const val WORD_JOINER: Char = '\u2060'

    /**
     * From this version on the platform's phrase style keeps Korean words whole; below it the joiners do. Android 13
     * brought phrase breaking for Japanese (bunsetsu), but Korean `keep-all` is not guaranteed there, and the joiners
     * give exactly that result (breaks at spaces only) where the platform already does it, so they are safe up to
     * API 34.
     */
    const val KOREAN_PHRASES_SDK: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** [text] as [phraseBreakable] draws it on API level [sdkInt]. Pure. */
    fun forSdk(text: String, sdkInt: Int): String = when {
        text.isEmpty() -> text
        sdkInt < Build.VERSION_CODES.TIRAMISU && text.codePoints().anyMatch(::isKana) -> join(text, ::japaneseBreak)
        sdkInt < KOREAN_PHRASES_SDK && text.codePoints().anyMatch(::isHangul) -> join(text) { _, _ -> false }
        else -> text
    }

    /** [text] with its Japanese phrases or Korean words kept together, whatever the API level ([forSdk] below 33). */
    fun keepPhrasesTogether(text: String): String = forSdk(text, Build.VERSION_CODES.S_V2)

    /** [text] with [WORD_JOINER] between neighbouring grapheme clusters where [breakAllowed] says no. */
    private fun join(text: String, breakAllowed: (Kind, Kind) -> Boolean): String {
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val out = StringBuilder(text.length * 2)
        var start = characters.first()
        var previous: Kind? = null
        var end = characters.next()
        while (end != BreakIterator.DONE) {
            val kind = kindOf(text.codePointAt(start), previous)
            if (previous != null && previous != Kind.Space && kind != Kind.Space && previous != Kind.Joiner &&
                kind != Kind.Joiner && !breakAllowed(previous, kind)
            ) {
                out.append(WORD_JOINER)
            }
            out.append(text, start, end)
            previous = kind
            start = end
            end = characters.next()
        }
        return out.toString()
    }

    /** Whether a Japanese line may break between a character of kind [before] and one of kind [after]. */
    private fun japaneseBreak(before: Kind, after: Kind): Boolean = when {
        after == Kind.Close || after == Kind.Stop -> false
        before == Kind.Open -> false
        before == Kind.Stop -> true
        before == Kind.Close -> after != Kind.Hiragana
        before == Kind.Hiragana -> after != Kind.Hiragana
        else -> after == Kind.Open
    }

    private enum class Kind { Space, Joiner, Open, Close, Stop, Hiragana, Word }

    private fun kindOf(codePoint: Int, previous: Kind?): Kind = when {
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> Kind.Space
        // A joiner already in the text (a text drawn twice) is left alone.
        codePoint == WORD_JOINER.code -> Kind.Joiner
        // The long-vowel mark continues the run before it (katakana, or hiragana as in "すごーい").
        codePoint == PROLONGED_SOUND_MARK -> if (previous == Kind.Hiragana) Kind.Hiragana else Kind.Word
        codePoint in HIRAGANA -> Kind.Hiragana
        Character.isBmpCodePoint(codePoint) && codePoint.toChar() in STOPS -> Kind.Stop
        else -> when (Character.getType(codePoint)) {
            START_PUNCTUATION, INITIAL_QUOTE -> Kind.Open
            END_PUNCTUATION, FINAL_QUOTE -> Kind.Close
            else -> Kind.Word
        }
    }

    private val START_PUNCTUATION = Character.START_PUNCTUATION.toInt()
    private val INITIAL_QUOTE = Character.INITIAL_QUOTE_PUNCTUATION.toInt()
    private val END_PUNCTUATION = Character.END_PUNCTUATION.toInt()
    private val FINAL_QUOTE = Character.FINAL_QUOTE_PUNCTUATION.toInt()

    private fun isKana(codePoint: Int): Boolean =
        codePoint in HIRAGANA || codePoint in KATAKANA || codePoint in KATAKANA_PHONETIC || codePoint in HALFWIDTH_KATAKANA

    private fun isHangul(codePoint: Int): Boolean =
        codePoint in HANGUL_SYLLABLES || codePoint in HANGUL_JAMO || codePoint in HANGUL_COMPATIBILITY_JAMO

    private val HIRAGANA = 0x3041..0x309F
    private val KATAKANA = 0x30A0..0x30FF
    private val KATAKANA_PHONETIC = 0x31F0..0x31FF
    private val HALFWIDTH_KATAKANA = 0xFF66..0xFF9F
    private val HANGUL_SYLLABLES = 0xAC00..0xD7A3
    private val HANGUL_JAMO = 0x1100..0x11FF
    private val HANGUL_COMPATIBILITY_JAMO = 0x3130..0x318F
    private const val PROLONGED_SOUND_MARK = 0x30FC

    /** Sentence and clause punctuation: never at a line start, a new phrase may follow. */
    private const val STOPS = "、。，．・：；！？‥…,.:;!?"
}
