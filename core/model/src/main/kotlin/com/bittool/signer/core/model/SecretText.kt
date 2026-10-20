package com.bittool.signer.core.model

import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.constantTimeEquals
import com.bittool.signer.core.model.util.utf8Size
import com.bittool.signer.core.model.util.wipe
import java.nio.CharBuffer

/**
 * A secret text (wallet password, recovery phrase, BIP39 passphrase) held in a `CharArray` that can be wiped
 * (DECISIONS.md §6). It deliberately is not a `CharSequence` and its [toString] is redacted, so it cannot end up in a
 * log, a crash report, a `SavedStateHandle` or a navigation argument by accident.
 *
 * Ownership rule used by every API in this project: **the caller owns a `SecretText` it passes in** and wipes it
 * (usually right after the call returns); the callee copies what it needs and wipes its own copies.
 */
class SecretText private constructor(private var chars: CharArray) : AutoCloseable {
    /** Number of UTF-16 units. */
    val length: Int get() = chars.size
    fun isEmpty(): Boolean = chars.isEmpty()
    fun isNotEmpty(): Boolean = chars.isNotEmpty()

    /** The live characters. Do not keep a reference beyond the current call and never convert them to a `String`. */
    fun chars(): CharArray = chars

    /** A read-only view over the live characters (no copy), for grapheme counting and policy evaluation. */
    fun view(): CharSequence = CharBuffer.wrap(chars).asReadOnlyBuffer()

    /** Grapheme clusters (Swift `count`). */
    fun graphemeCount(): Int = Graphemes.count(view())

    /** UTF-8 byte length (Swift `utf8.count`). */
    fun utf8Size(): Int = view().utf8Size()

    /** An independent copy the receiver must wipe. */
    fun copy(): SecretText = SecretText(chars.copyOf())

    /** Constant-time content comparison. */
    fun contentEquals(other: SecretText): Boolean = constantTimeEquals(chars, other.chars)

    /** Zeroes the characters and empties this instance. Idempotent. */
    fun wipe() {
        chars.wipe()
        chars = CharArray(0)
    }

    override fun close() = wipe()

    override fun toString(): String = "SecretText(<redacted>)"

    // Identity semantics on purpose: a content-based equals would be a timing oracle and invite comparisons.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** Copies [text] (a `TextFieldState.text` snapshot, typically); the caller clears its own field. */
        fun of(text: CharSequence): SecretText = SecretText(CharArray(text.length) { text[it] })

        /** Takes ownership of [chars] (no copy): wiping this instance wipes [chars]. */
        fun wrap(chars: CharArray): SecretText = SecretText(chars)

        /** An empty secret (e.g. "no BIP39 passphrase"). */
        fun empty(): SecretText = SecretText(CharArray(0))
    }
}

/** Runs [block] and wipes this secret afterwards, also when [block] throws. */
inline fun <R> SecretText.useAndWipe(block: (SecretText) -> R): R = try {
    block(this)
} finally {
    wipe()
}
