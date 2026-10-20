package com.bittool.signer.feature.sign.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The latest [value] that satisfied [keep], or [value] itself until one did. A page that is fading out (the Scan tab
 * switches Home / Review / Signature with a cross-fade) keeps drawing what it showed instead of going blank once the
 * store has moved on (Reject, Done).
 */
@Composable
internal fun <T> rememberLatest(value: T, keep: (T) -> Boolean): T {
    val holder = remember { arrayOfNulls<Any?>(1) }
    if (keep(value) || holder[0] == null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T
}
