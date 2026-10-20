package com.bittool.signer.core.model

import kotlin.coroutines.cancellation.CancellationException

/**
 * User-visible text that is resolved in the UI's current (per-app) locale, never at throw time. The model layer is
 * JVM-only and has no resources, so a message names its iOS English source string (the localisation key, DECISIONS.md
 * §4) and the UI resolves it with `UiText.resolve(...)` from `:core:l10n`.
 */
sealed interface UiText {
    /**
     * A string of the iOS catalogue, identified by its exact English text with Swift specifiers (`%@`, `%lld`,
     * positional `%1$@`). Resolved through `IosStrings.lookup(english)`; when the catalogue has no entry the English
     * is shown with [args] substituted ([fallbackEnglish]). [args] are `String`, `Int` or `Long`.
     */
    data class Ios(val english: String, val args: List<Any> = emptyList()) : UiText

    /** An Android-only string resource (`R.string.*` of `:core:l10n`). Only constructed in Android modules. */
    data class Resource(val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Text shown exactly as given: engine English, OS error text, data values. Never translated. */
    data class Verbatim(val text: String) : UiText

    companion object {
        /** Shorthand for [Ios]. */
        fun ios(english: String, vararg args: Any): UiText = Ios(english, args.toList())
    }
}

/**
 * [english] with its Swift specifiers replaced by [args] — what iOS shows for a key that has no translation. `%@` and
 * `%lld` consume arguments in order, `%1$@` / `%2$lld` pick by position, `%%` is a literal percent sign.
 */
fun UiText.Ios.fallbackEnglish(): String {
    val out = StringBuilder()
    var next = 0
    var i = 0
    val s = english
    while (i < s.length) {
        val c = s[i]
        if (c != '%' || i + 1 >= s.length) { out.append(c); i++; continue }
        if (s[i + 1] == '%') { out.append('%'); i += 2; continue }
        var j = i + 1
        var position: Int? = null
        val digitsStart = j
        while (j < s.length && s[j].isDigit()) j++
        if (j > digitsStart && j < s.length && s[j] == '$') {
            position = s.substring(digitsStart, j).toInt() - 1
            j++
        } else {
            j = i + 1
        }
        val consumed = when {
            s.startsWith("@", j) -> 1
            s.startsWith("lld", j) -> 3
            s.startsWith("ld", j) -> 2
            s.startsWith("d", j) -> 1
            else -> 0
        }
        if (consumed == 0) { out.append(c); i++; continue }
        val index = position ?: next++
        out.append(args.getOrNull(index)?.toString() ?: "")
        i = j + consumed
    }
    return out.toString()
}

/** A plain-text rendering for logs and `Throwable.message` (no secrets ever reach a [UiText]). */
fun UiText.debugText(): String = when (this) {
    is UiText.Ios -> fallbackEnglish()
    is UiText.Resource -> "R.string#$id$args"
    is UiText.Verbatim -> text
}

/**
 * Base class of every controlled, user-presentable failure (iOS `LocalizedError`): [text] is what the UI shows.
 * Subclasses: [AppIssue] (iOS `AppIssue.message`), `VaultError` and `Bip39Error` (`:core:crypto`), …
 */
open class SignerException(val text: UiText, cause: Throwable? = null) : Exception(text.debugText(), cause)

/** iOS `AppIssue.message(String)`: a host-side message from the iOS catalogue (models-state.md §3.5). */
class AppIssue(english: String, vararg args: Any) : SignerException(UiText.Ios(english, args.toList()))

/**
 * What the UI shows for [this] failure, or null when nothing must be shown: a cancellation (late result, epoch change)
 * is silent everywhere (models-state.md §5.2). [SignerException]s carry their text; anything else (engine errors,
 * file-system errors) is shown verbatim like iOS `error.localizedDescription`.
 */
fun Throwable.toUiText(): UiText? = when (this) {
    is CancellationException -> null
    is SignerException -> text
    else -> UiText.Verbatim(localizedMessage ?: message ?: javaClass.simpleName)
}
