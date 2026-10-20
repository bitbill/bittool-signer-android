package com.bittool.signer.core.model

import com.bittool.signer.core.model.util.trimWhitespaces
import com.bittool.signer.core.model.util.utf8Size

/** QR error-correction levels the app uses (models-state.md §3.4, design-system.md §5.12). */
enum class QrErrorCorrection {
    L, M;

    companion object {
        /** iOS `QRRenderer`: M when the frame is at most 600 UTF-8 bytes, else L (Wallet Level frames are L). */
        fun forPayload(text: String): QrErrorCorrection = if (text.utf8Size() <= 600) M else L
    }
}

/**
 * A QR symbol as modules (true = dark), without a quiet zone: callers draw the white plate and its 18 dp inset, plus a
 * one-module margin when matching CoreImage (design-system.md §5.12). Immutable.
 */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    init {
        require(size > 0 && modules.size == size * size) { "modules must be size × size" }
    }

    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    override fun equals(other: Any?): Boolean = other is QrMatrix && other.size == size && other.modules.contentEquals(modules)
    override fun hashCode(): Int = 31 * size + modules.contentHashCode()
    override fun toString(): String = "QrMatrix(${size}x$size)"
}

/** Thrown when a text does not fit a QR symbol. */
class QrEncodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encodes one QR frame (implemented in `:core:scanner` with ZXing, bound by Hilt). Rules: error correction
 * [QrErrorCorrection.forPayload]; byte/alphanumeric/numeric mode chosen automatically; `CHARACTER_SET=UTF-8` only when
 * the text has non-ASCII (no ECI otherwise, so upper-case UR frames use alphanumeric mode); the text is encoded exactly
 * as given (never trimmed or re-cased). CPU-bound: call it off the main thread. Throws [QrEncodingException].
 */
fun interface QrCodeEncoder {
    fun encode(text: String): QrMatrix
}

/**
 * Bundled token artwork by content-hash ID (models-state.md §11). Implemented by `:core:data`'s `TokenIconStore`
 * (memory-mapped `TokenIcons.pack`); `:core:ui`'s `TokenIcon` decodes and caches the PNG. Never touches the network;
 * null for an unknown, empty or malformed ID (e.g. `"../../etc/passwd"`).
 */
fun interface TokenIconSource {
    suspend fun png(iconId: String): ByteArray?

    companion object {
        val Empty: TokenIconSource = TokenIconSource { null }
    }
}

/** The two bundled legal documents (activity-settings.md §10). Titles are English on every language (by decision). */
enum class LegalDocument(val englishTitle: String, val assetPath: String, val testTag: String) {
    Terms("Terms of Use", "legal/TERMS_OF_USE.txt", "legal.terms"),
    Privacy("Privacy Policy", "legal/PRIVACY_POLICY.txt", "legal.privacy"),
}

/** One rendered piece of a legal document. [text] may contain `**bold**` spans (Android renders them bold). */
data class LegalBlock(val id: Int, val kind: Kind, val text: String) {
    enum class Kind { Version, Heading, Bullet, Paragraph }
}

/** iOS `LegalDocumentView.blocks(of:title:)` (LegalDocumentTests). */
object LegalDocumentParser {
    /** The text shown when a bundled document cannot be read. Verbatim English on iOS. */
    const val MISSING = "This document is missing from the app bundle."

    /**
     * Plain text with `## ` headings and `- ` bullets; a blank line ends a paragraph and wrapped lines join with one
     * space; a first line equal to [title] is dropped; a leading `Version …` line is a version block. Lines are split
     * on every Unicode newline and trimmed of spaces and tabs.
     */
    fun parse(text: String, title: String): List<LegalBlock> {
        val blocks = ArrayList<LegalBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isEmpty()) return
            blocks += LegalBlock(blocks.size, LegalBlock.Kind.Paragraph, paragraph.joinToString(" "))
            paragraph.clear()
        }
        for (line in splitLines(text).map { it.trimWhitespaces() } + "") {
            if (line == title && blocks.isEmpty() && paragraph.isEmpty()) continue
            if (line.startsWith("Version ") && blocks.isEmpty() && paragraph.isEmpty()) {
                blocks += LegalBlock(blocks.size, LegalBlock.Kind.Version, line)
                continue
            }
            when {
                line.isEmpty() -> flush()
                line.startsWith("## ") -> { flush(); blocks += LegalBlock(blocks.size, LegalBlock.Kind.Heading, line.substring(3)) }
                line.startsWith("- ") -> { flush(); blocks += LegalBlock(blocks.size, LegalBlock.Kind.Bullet, line.substring(2)) }
                else -> paragraph += line
            }
        }
        flush()
        return blocks
    }

    /** Swift `split(whereSeparator: \.isNewline)` keeping empty pieces: LF, CR LF, CR, VT, FF, NEL, LS, PS. */
    private fun splitLines(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                out += current.toString(); current.clear(); i += 2; continue
            }
            if (c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                out += current.toString(); current.clear(); i++; continue
            }
            current.append(c)
            i++
        }
        out += current.toString()
        return out
    }

}
