package com.bittool.signer.core.crypto.internal

/** Zeroes every word (key-equivalent hash states, schedules); the byte and char variants live in `:core:model`. */
internal fun IntArray.wipe() = fill(0)

/** Zeroes every word. */
internal fun LongArray.wipe() = fill(0L)

/**
 * True when [chars] contains a UTF-16 surrogate that is not part of a pair. Such text cannot exist in Swift, and
 * `utf8Bytes` would encode the lone unit as `?`, silently making two different secrets the same key input
 * (vault-crypto.md §17.10 item 10), so secret inputs that contain one are refused.
 */
internal fun hasUnpairedSurrogate(chars: CharArray): Boolean {
    var i = 0
    while (i < chars.size) {
        val c = chars[i]
        if (c.isHighSurrogate()) {
            if (i + 1 >= chars.size || !chars[i + 1].isLowSurrogate()) return true
            i += 2
            continue
        }
        if (c.isLowSurrogate()) return true
        i++
    }
    return false
}
