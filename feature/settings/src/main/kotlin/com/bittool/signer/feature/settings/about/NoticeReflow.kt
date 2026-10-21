package com.bittool.signer.feature.settings.about

/** One displayed piece of a notices paragraph ([NoticeReflow]). */
internal sealed interface NoticeBlock {
    /** Text lines, each drawn as a wrapping paragraph of its own. */
    data class Text(val lines: List<NoticeLine>) : NoticeBlock

    /** A separator line of the file (`=====…`), drawn as a divider. */
    data object Rule : NoticeBlock
}

/**
 * One line as the file means it: [text] with the hard line breaks of a wrapped sentence joined by spaces and without
 * its leading spaces; [indent] the leading spaces of its first line and [hangingIndent] those of the lines it was
 * joined from (Apache's "   2. Grant…" over "      this License…"), both in characters; [centered] for a line the
 * file centres with spaces ("Apache License").
 */
internal data class NoticeLine(val text: String, val indent: Int = 0, val hangingIndent: Int = indent, val centered: Boolean = false)

/**
 * Readable third-party notices on a phone (QA sign-16). The files are plain text hard-wrapped at about 70–80
 * columns; drawn as they are, every line re-wraps a little short of the screen and leaves a ragged half line, and the
 * 72-character `=====` separators break onto two lines. The words stay exactly as written: only the line breaks a
 * sentence was wrapped with become spaces, so it wraps to the screen. Line breaks that carry meaning stay — a headline
 * ("pkg 1.0 — MIT") over its URL, one copyright holder per line, list items, the end of a sentence short of the wrap
 * column, a line that introduces what follows (":") — and indentation is kept as an indent, so Apache's numbered
 * sections keep their hanging indents.
 */
internal object NoticeReflow {
    /** Lines longer than this were not wrapped by hand (some notices are written one paragraph per line). */
    private const val WRAP_LIMIT = 80

    /** A paragraph whose longest wrapped line is shorter than this is laid out by line, not wrapped. */
    private const val MIN_WRAP_COLUMN = 60

    /** A line within this many characters of the paragraph's wrap column was broken by the wrapping. */
    private const val WRAP_SLACK = 16

    /** Short lines are headings, names or the end of a sentence, never wrapped prose. */
    private const val MIN_WRAPPED_TEXT = 40

    /** Indentation this deep centres a line by hand (the files are about 80 columns wide). */
    private const val CENTRED_INDENT = 16

    private val separator = Regex("""^\s*([=\-_*~#])\1{9,}\s*$""")
    private val startsItem = Regex("""^(\d{1,3}[.)]|\(?[0-9a-zA-Z]{1,4}\)|[-*•])\s""")
    /** A copyright holder line or a URL; a lower-case "copyright" continues a sentence ("…irrevocable / copyright license"). */
    private val startsOwnLine = listOf("Copyright", "(c)", "(C)", "©", "http://", "https://", "<")

    /** The blocks of one paragraph (the text between two blank lines). */
    fun blocks(paragraph: String): List<NoticeBlock> {
        val blocks = mutableListOf<NoticeBlock>()
        val run = mutableListOf<String>()
        fun flush() {
            if (run.isNotEmpty()) blocks += NoticeBlock.Text(lines(run.toList()))
            run.clear()
        }
        for (line in paragraph.lines()) {
            if (separator.matches(line)) {
                flush()
                blocks += NoticeBlock.Rule
            } else {
                run += line
            }
        }
        flush()
        return blocks
    }

    /** Consecutive text lines of a paragraph, the wrapped sentences joined. */
    fun lines(physical: List<String>): List<NoticeLine> {
        val column = physical.map { it.trimEnd().length }.filter { it <= WRAP_LIMIT }.maxOrNull() ?: 0
        val result = mutableListOf<NoticeLine>()
        val text = StringBuilder()
        var joined = 0
        var indent = 0
        var hanging = 0
        physical.forEachIndexed { index, line ->
            val words = line.trim()
            when {
                joined == 0 -> {
                    indent = line.leadingSpaces()
                    hanging = indent
                    text.append(words)
                }
                else -> {
                    // The first continuation line sets the hanging indent.
                    if (joined == 1) hanging = line.leadingSpaces()
                    if (!text.endsWithHyphenatedWord()) text.append(' ')
                    text.append(words)
                }
            }
            joined++
            val next = physical.getOrNull(index + 1)
            if (next == null || !wrapsInto(line, next, column)) {
                val centred = joined == 1 && indent >= CENTRED_INDENT
                result += if (centred) NoticeLine(text.toString(), centered = true) else NoticeLine(text.toString(), indent, hanging)
                text.clear()
                joined = 0
            }
        }
        // A centred title block whose first line lost its centring spaces (the engine's notices start "Apache License"
        // at the margin, its version and URL centred under it).
        val first = result.firstOrNull()
        if (first != null && result.size > 1 && result.drop(1).all { it.centered } && !first.centered && first.indent == 0 &&
            first.text.length < MIN_WRAPPED_TEXT
        ) {
            result[0] = first.copy(centered = true)
        }
        return result
    }

    private fun wrapsInto(line: String, next: String, column: Int): Boolean {
        val length = line.trimEnd().length
        val nextWords = next.trim()
        return column >= MIN_WRAP_COLUMN &&
            length <= WRAP_LIMIT &&
            length >= column - WRAP_SLACK &&
            line.trim().length >= MIN_WRAPPED_TEXT &&
            !line.trimEnd().endsWith(':') &&
            nextWords.isNotEmpty() &&
            !startsItem.containsMatchIn(nextWords) &&
            startsOwnLine.none { nextWords.startsWith(it) }
    }

    private fun String.leadingSpaces(): Int = takeWhile { it == ' ' }.length

    private fun StringBuilder.endsWithHyphenatedWord(): Boolean =
        length >= 2 && this[length - 1] == '-' && this[length - 2].isLetter()
}
