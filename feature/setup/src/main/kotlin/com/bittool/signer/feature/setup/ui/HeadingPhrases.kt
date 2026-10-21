package com.bittool.signer.feature.setup.ui

import android.os.Build
import java.text.BreakIterator
import java.util.Locale

/**
 * Phrase-boundary line breaks for the setup large titles on Android 8–12L (QA setup-15, regression-13).
 *
 * The titles ask for `LineBreak.Heading`, whose phrase word break (`LINE_BREAK_WORD_STYLE_PHRASE`) only exists from
 * API 33. Below it a Japanese title breaks between any two characters ("ウォレットを作成しま / した") and a Korean one
 * between any two syllables, where iOS keeps phrases (bunsetsu) and Korean words whole. [keepTogether] gives those
 * versions the same result: it puts a WORD JOINER (U+2060, invisible, "no break here" for the line breaker, UAX #14
 * class WJ) between the characters of each phrase, so lines can only break between phrases (and at spaces). A phrase
 * longer than a whole line still breaks inside, as the line breaker always does. From API 33 the text is returned
 * unchanged: the platform's own phrase breaking applies. Text the reader sees keeps its exact characters; only the
 * drawn copy carries the joiners ([LargeTitle] gives accessibility the original).
 *
 * Phrases, read from the characters themselves (no dictionary is needed for titles):
 * - **Japanese** (any kana): a phrase is optional opening brackets, a run of kanji, katakana, Latin letters or digits,
 *   the hiragana that follow it (okurigana, particles and endings: "ウォレットを", "作成しました"), then closing
 *   brackets and punctuation ("。", "、", "」"). A hiragana run that starts the text or follows punctuation is a phrase of
 *   its own ("この"). A particle right after a closing bracket stays with it ("」を").
 * - **Korean** (any Hangul): the words between spaces, as iOS and Korean typesetting break them.
 * - Anything else, Chinese included (which breaks between any two characters), is returned unchanged.
 */
internal object HeadingPhrases {
    const val WORD_JOINER: Char = '⁠'

    fun keepTogether(text: String, sdk: Int = Build.VERSION.SDK_INT): String {
        if (sdk >= Build.VERSION_CODES.TIRAMISU || text.isEmpty()) return text
        return when {
            text.codePoints().anyMatch(::isKana) -> join(text, ::japaneseBreak)
            text.codePoints().anyMatch(::isHangul) -> join(text) { _, _ -> false }
            else -> text
        }
    }

    /** [text] with [WORD_JOINER] between neighbouring characters (grapheme clusters) where [breakAllowed] says no. */
    private fun join(text: String, breakAllowed: (Kind, Kind) -> Boolean): String {
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val out = StringBuilder(text.length * 2)
        var start = characters.first()
        var previous: Kind? = null
        var end = characters.next()
        while (end != BreakIterator.DONE) {
            val kind = kindOf(text.codePointAt(start), previous)
            if (previous != null && previous != Kind.Space && kind != Kind.Space && !breakAllowed(previous, kind)) {
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

    private enum class Kind { Space, Open, Close, Stop, Hiragana, Word }

    private fun kindOf(codePoint: Int, previous: Kind?): Kind = when {
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> Kind.Space
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
